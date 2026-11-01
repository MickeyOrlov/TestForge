package io.testforge.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.artifact.ArtifactSink;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Judges a finished run: reads its JUnit XML and run index, scores the
 * dimensions, writes {@code quality.md} / {@code quality.json} and registers
 * them in the current run's index.
 *
 * <pre>{@code
 * @Test
 * void theBuildIsAcceptable() {
 *     qualityRunner.assertAcceptable();
 * }
 * }</pre>
 *
 * Run it in a CI step after the test jobs, with their results in place.
 */
public class QualityRunner {

    static final String SOURCE = "module-quality";
    /** Runs without a FAIL, this one included, before the report suggests promoting the baseline. */
    static final int STEADY_RUNS = 5;
    private static final List<String> REVISION_VARIABLES = List.of("GITHUB_SHA", "CI_COMMIT_SHA", "GIT_COMMIT");
    private static final List<String> BRANCH_VARIABLES = List.of("GITHUB_REF_NAME", "CI_COMMIT_REF_NAME",
            "GIT_BRANCH", "BRANCH_NAME");
    private static final List<String> JOB_VARIABLES = List.of("GITHUB_RUN_ID", "CI_JOB_ID", "BUILD_NUMBER");

    private final QualityProperties properties;
    private final ObjectMapper mapper;
    private final ArtifactSink sink;
    private final java.time.Clock clock;

    public QualityRunner(QualityProperties properties, ObjectMapper mapper, ArtifactSink sink) {
        this(properties, mapper, sink, java.time.Clock.systemUTC());
    }

    /** @param clock today's date, for accepted entries that expire */
    QualityRunner(QualityProperties properties, ObjectMapper mapper, ArtifactSink sink, java.time.Clock clock) {
        this.properties = properties;
        this.mapper = mapper;
        this.sink = sink == null ? ArtifactSink.NO_OP : sink;
        this.clock = clock;
    }

    /** Assesses and writes the report; never fails on a bad product, only on unreadable inputs. */
    public QualityReport run() {
        return run(tests());
    }

    private JunitResults.Read tests() {
        return JunitResults.read(properties.junitResults().stream().map(Path::of).toList(),
                properties.resultsNotBefore());
    }

    private QualityReport run(JunitResults.Read read) {
        List<JunitResults.TestCase> tests = read.tests();
        Path artifacts = Path.of(properties.artifactsRoot());
        RunIndex index = RunIndex.find(artifacts, properties.runId(), mapper).orElse(null);
        Path baselineFile = Path.of(properties.baselineFile());
        QualityBaseline baseline = QualityBaseline.read(baselineFile, mapper).orElse(null);
        List<String> inputs = new ArrayList<>(properties.junitResults());
        read.stale().forEach(file -> inputs.add(file + " (dropped: older than " + properties.resultsNotBefore() + ")"));
        inputs.add(index == null ? properties.artifactsRoot() + " (no run index)" : index.manifest().toString());
        inputs.add(baseline == null ? baselineFile + " (no baseline: every issue is new)" : baselineFile.toString());
        String runId = index == null ? null : index.runId();
        QualityProperties.HistoryProperties historyProperties = properties.history();
        String revision = revision();
        QualityHistory history = historyProperties.enabled()
                ? QualityHistory.read(Path.of(historyProperties.dir()), runId, historyProperties.window(), mapper)
                : QualityHistory.empty();
        if (historyProperties.enabled()) {
            inputs.add(historyProperties.dir() + " (history: " + history.entries().size() + " earlier runs"
                    + (revision == null ? ", revision unknown" : ", revision " + revision)
                    + (history.skipped().isEmpty() ? "" : ", skipped " + String.join(", ", history.skipped())) + ")");
        }
        QualityReport assessed = QualityAssessor.assess(runId, tests, index, baseline, inputs, properties, mapper,
                history, revision);
        java.time.LocalDate today = java.time.LocalDate.now(clock);
        QualityReport compared = assessed.withDimensions(baseline == null ? null : baseline.runId(),
                assessed.dimensions().stream()
                        .map(d -> d.withDelta(QualityBaseline.compare(d, baseline, properties.accepted(),
                                properties.acceptedUntil(), today)))
                        .toList());
        List<String> notes = new ArrayList<>(acceptedNotes(compared, today));
        notes.addAll(HistoryDimensions.baselineNotes(compared, history, baseline != null, STEADY_RUNS));
        compared = compared.withNotes(notes).withLabels(labels(revision))
                .withComponents(QualityComponents.assign(compared.dimensions(), properties.components()));
        QualityReport report = QualityReportWriter.write(Path.of(properties.outputDir()), compared, mapper);
        if (historyProperties.enabled()) {
            QualityHistory.record(Path.of(historyProperties.dir()),
                    QualityHistory.of(report, tests, revision, Instant.now(), latencies(index)),
                    historyProperties.keep(), mapper);
        }
        Map<String, String> metadata = Map.of("overall", report.overall().name());
        sink.registerFile(SOURCE, "quality", QualityReportWriter.MARKDOWN, Path.of(report.reportMarkdown()),
                "text/markdown", metadata);
        sink.registerFile(SOURCE, "quality", QualityReportWriter.JSON, Path.of(report.reportJson()),
                "application/json", metadata);
        Path output = Path.of(report.reportJson()).getParent();
        sink.registerFile(SOURCE, "quality", QualityReportWriter.SUMMARY, output.resolve(QualityReportWriter.SUMMARY),
                "text/markdown", metadata);
        sink.registerFile(SOURCE, "quality", QualityReportWriter.METRICS, output.resolve(QualityReportWriter.METRICS),
                "application/openmetrics-text", metadata);
        return report;
    }

    /**
     * Accepted entries that stopped letting their issue through (expired) or no longer match an
     * issue of this run — candidates to delete from {@code forge.quality.accepted}.
     */
    private List<String> acceptedNotes(QualityReport report, java.time.LocalDate today) {
        java.util.Set<String> present = new java.util.HashSet<>();
        report.dimensions().forEach(d -> d.issues().forEach(issue -> present.add(d.name() + " " + issue)));
        List<String> notes = new ArrayList<>();
        for (String entry : properties.accepted()) {
            java.time.LocalDate until = properties.acceptedUntil().get(entry);
            if (QualityBaseline.expired(until, today) && present.contains(entry)) {
                notes.add("accepted until " + until + ", expired — counted as new: " + entry);
            } else if (!present.contains(entry)) {
                notes.add("accepted but not seen in this run — delete it once that holds: " + entry);
            }
        }
        return notes;
    }

    /** Revision, branch and CI job from the CI's own variables, then {@code run-labels} on top. */
    private Map<String, String> labels(String revision) {
        Map<String, String> labels = new java.util.TreeMap<>();
        if (revision != null) {
            labels.put("revision", revision);
        }
        firstSet(BRANCH_VARIABLES).ifPresent(branch -> labels.put("branch", branch));
        firstSet(JOB_VARIABLES).ifPresent(job -> labels.put("job", job));
        labels.putAll(properties.runLabels());
        return labels;
    }

    private static java.util.Optional<String> firstSet(List<String> variables) {
        return variables.stream().map(System::getenv).filter(v -> v != null && !v.isBlank()).findFirst();
    }

    /** A history entry keeps no latency it cannot read; a report of an unknown layout is just left out. */
    private java.util.Map<String, Long> latencies(RunIndex index) {
        try {
            return ModuleDimensions.latencies(index, mapper);
        } catch (RuntimeException e) {
            return java.util.Map.of();
        }
    }

    /** The commit this run tested: configured, or what the CI exposes; {@code null} when neither says. */
    private String revision() {
        if (properties.history().revision() != null) {
            return properties.history().revision();
        }
        return firstSet(REVISION_VARIABLES).orElse(null);
    }

    /**
     * Promotes this run to the baseline the next runs are compared with —
     * always an explicit call in a reviewed change, never a side effect of
     * the check.
     */
    public QualityReport writeBaseline() {
        JunitResults.Read read = tests();
        QualityReport report = run(read);
        QualityBaseline.of(report, read.tests()).write(Path.of(properties.baselineFile()), mapper);
        return report;
    }

    /**
     * {@link #run()}, then fails when a {@code fail-on} dimension FAILed with
     * an issue that is new — not in the baseline, not accepted — or nothing
     * could be measured. Fails closed while
     * {@code forge.quality.enabled} is false: a gate job that forgot it is
     * told, not silently green.
     */
    public QualityReport assertAcceptable() {
        if (!properties.enabled()) {
            throw new QualityException("The quality gate is disabled: set forge.quality.enabled=true in the job "
                    + "that runs it (run() reports without the gate)");
        }
        QualityReport report = run();
        if (report.overall() == QualityStatus.NOT_MEASURED) {
            throw new QualityException("Nothing to assess: no executed tests in " + properties.junitResults()
                    + " and no run index under " + properties.artifactsRoot() + ". See " + report.reportMarkdown());
        }
        List<QualityDimension> missing = report.dimensions().stream()
                .filter(d -> d.status() == QualityStatus.NOT_MEASURED && properties.required().contains(d.name()))
                .toList();
        if (!missing.isEmpty()) {
            throw new QualityException("Required dimensions not measured: " + missing.stream()
                    .map(d -> d.name() + " (" + d.facts().getFirst() + ")").collect(Collectors.joining("; "))
                    + ". See " + report.reportMarkdown());
        }
        // an incomplete security run never passes a gate that asked for security, baseline or not
        boolean securityIncomplete = report.dimension(QualityDimension.SECURITY)
                .filter(d -> d.issues().contains(ModuleDimensions.SECURITY_INCOMPLETE)).isPresent();
        if (properties.failOn().contains(QualityDimension.SECURITY) && securityIncomplete) {
            throw new QualityException("Security measurement incomplete: the security gate needs complete coverage. "
                    + "See " + report.reportMarkdown());
        }
        List<String> failed = new ArrayList<>(report.dimensions().stream()
                .filter(d -> d.status() == QualityStatus.FAIL && properties.failOn().contains(d.name()))
                // a FAIL with no issue key (a rate over its limit) cannot be "known": it fails
                .filter(d -> d.issues().isEmpty() || !d.newIssues().isEmpty())
                // a budget lets a FAIL through while its new issues stay within it
                .filter(d -> d.issues().isEmpty() || !withinBudget(d))
                .map(QualityRunner::describe)
                .toList());
        failed.addAll(policyFailures(report));
        if (!failed.isEmpty()) {
            throw new QualityException("Quality gate failed on " + String.join("; ", failed) + ". See "
                    + report.reportMarkdown());
        }
        return report;
    }

    private boolean withinBudget(QualityDimension dimension) {
        QualityProperties.GateProperties gate = properties.gate().get(dimension.name());
        return gate != null && gate.maxNewIssues() != null && dimension.newIssues().size() <= gate.maxNewIssues();
    }

    /**
     * {@code forge.quality.gate}: a score below {@code min-score} or new issues beyond
     * {@code max-new-issues}, whatever the status. A NOT MEASURED dimension is left to {@code required}.
     */
    private List<String> policyFailures(QualityReport report) {
        List<String> failures = new ArrayList<>();
        properties.gate().forEach((name, gate) -> report.dimension(name)
                .filter(d -> d.status() != QualityStatus.NOT_MEASURED)
                .ifPresent(d -> {
                    if (gate.minScore() != null && d.score() < gate.minScore()) {
                        failures.add(name + " (score " + d.score() + " below the gate's " + gate.minScore() + ")");
                    }
                    if (gate.maxNewIssues() != null && d.newIssues().size() > gate.maxNewIssues()
                            && !(d.status() == QualityStatus.FAIL && properties.failOn().contains(name))) {
                        failures.add(name + " (" + d.newIssues().size() + " new issues, budget "
                                + gate.maxNewIssues() + ")");
                    }
                }));
        return failures;
    }

    private static String describe(QualityDimension dimension) {
        List<String> fresh = dimension.newIssues();
        String issues = fresh.isEmpty() ? "" : ", new: " + String.join(", ",
                fresh.subList(0, Math.min(5, fresh.size()))) + (fresh.size() > 5 ? " (+" + (fresh.size() - 5) + ")" : "");
        return dimension.name() + " (" + dimension.facts().getFirst() + issues + ")";
    }
}
