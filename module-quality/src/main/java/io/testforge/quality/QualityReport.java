package io.testforge.quality;

import java.util.List;
import java.util.Optional;

/**
 * What the assessment concluded about one run.
 *
 * @param runId     the run index it read, or {@code null} when there was none
 * @param overall   the worst status among measured dimensions; {@code NOT_MEASURED} when none was
 * @param meanScore the unweighted mean of measured scores, for trend lines; {@code null} when none was
 * @param inputs    the files and directories read, so a reader can check the numbers
 * @param baselineRunId the run the comparison is against, {@code null} without a baseline
 * @param notes     what the history suggests to a reader — never acted on by the assessment
 * @param labels    the run's metadata: revision, branch, CI job, configured {@code run-labels}
 * @param components issues by owning component ({@code forge.quality.components}); empty without them
 * @param gate       what {@code assertAcceptable()} decides on this report; {@code null} before it is judged
 */
@com.fasterxml.jackson.annotation.JsonPropertyOrder({"formatVersion", "runId", "baselineRunId", "overall", "meanScore", "measured",
        "gate", "labels", "notes", "components", "dimensions", "inputs", "reportMarkdown", "reportJson"})
public record QualityReport(
        String runId,
        String baselineRunId,
        QualityStatus overall,
        Integer meanScore,
        List<QualityDimension> dimensions,
        List<String> inputs,
        String reportMarkdown,
        String reportJson,
        List<String> notes,
        java.util.Map<String, String> labels,
        List<Component> components,
        Gate gate) {

    /**
     * The gate's verdict on this report — the same rules {@code assertAcceptable()} enforces.
     *
     * @param verdict  PASS or FAIL
     * @param failures what fails it, one line each; empty on PASS
     * @param dryRun   what would fail it on the dimensions whose gate is a dry run
     * @param dimensions each dimension's part in it: {@value #ENFORCED} ({@code fail-on}, {@code required}
     *                 or a limit), {@value #DRY_RUN}, or {@value #REPORT_ONLY} — a FAIL there fails nothing
     */
    public record Gate(QualityStatus verdict, List<String> failures, List<String> dryRun,
                       java.util.Map<String, String> dimensions) {

        public static final String ENFORCED = "enforced";
        public static final String DRY_RUN = "dry-run";
        public static final String REPORT_ONLY = "report only";

        public Gate {
            failures = List.copyOf(failures);
            dryRun = List.copyOf(dryRun);
            dimensions = dimensions == null ? java.util.Map.of()
                    : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(dimensions));
        }

        /** {@code report only} for a dimension the gate does not know. */
        public String role(String dimension) {
            return dimensions.getOrDefault(dimension, REPORT_ONLY);
        }
    }

    /**
     * One component's share of the run.
     *
     * @param status    the worst status among the dimensions it has an issue in; PASS with none
     * @param issues    {@code "<dimension> <issue>"} keys it owns
     * @param newIssues how many of them the baseline did not have and nobody accepted
     */
    public record Component(String name, String owner, QualityStatus status, List<String> issues, int newIssues) {

        public Component {
            issues = List.copyOf(issues);
        }
    }

    public QualityReport {
        dimensions = List.copyOf(dimensions);
        inputs = List.copyOf(inputs);
        notes = notes == null ? List.of() : List.copyOf(notes);
        labels = labels == null ? java.util.Map.of() : java.util.Collections.unmodifiableMap(
                new java.util.TreeMap<>(labels));
        components = components == null ? List.of() : List.copyOf(components);
    }

    public QualityReport(String runId, String baselineRunId, QualityStatus overall, Integer meanScore,
                         List<QualityDimension> dimensions, List<String> inputs, String reportMarkdown,
                         String reportJson, List<String> notes, java.util.Map<String, String> labels,
                         List<Component> components) {
        this(runId, baselineRunId, overall, meanScore, dimensions, inputs, reportMarkdown, reportJson, notes, labels,
                components, null);
    }

    public QualityReport(String runId, String baselineRunId, QualityStatus overall, Integer meanScore,
                         List<QualityDimension> dimensions, List<String> inputs, String reportMarkdown,
                         String reportJson, List<String> notes) {
        this(runId, baselineRunId, overall, meanScore, dimensions, inputs, reportMarkdown, reportJson, notes, null,
                null);
    }

    public QualityReport(String runId, String baselineRunId, QualityStatus overall, Integer meanScore,
                         List<QualityDimension> dimensions, List<String> inputs, String reportMarkdown,
                         String reportJson) {
        this(runId, baselineRunId, overall, meanScore, dimensions, inputs, reportMarkdown, reportJson, List.of());
    }

    /** The layout of {@code quality.json}, described by {@code quality.schema.json}. */
    public static final int FORMAT_VERSION = 1;

    @com.fasterxml.jackson.annotation.JsonProperty("formatVersion")
    public int formatVersion() {
        return FORMAT_VERSION;
    }

    /** How many dimensions this run could actually judge, of all of them. */
    @com.fasterxml.jackson.annotation.JsonProperty("measured")
    public int measured() {
        return (int) dimensions.stream().filter(d -> d.status() != QualityStatus.NOT_MEASURED).count();
    }

    public Optional<QualityDimension> dimension(String name) {
        return dimensions.stream().filter(d -> d.name().equals(name)).findFirst();
    }

    QualityReport withFiles(String markdown, String json) {
        return new QualityReport(runId, baselineRunId, overall, meanScore, dimensions, inputs, markdown, json, notes,
                labels, components, gate);
    }

    QualityReport withDimensions(String baseline, List<QualityDimension> compared) {
        return new QualityReport(runId, baseline, overall, meanScore, compared, inputs, reportMarkdown, reportJson,
                notes, labels, components, gate);
    }

    QualityReport withNotes(List<String> added) {
        return new QualityReport(runId, baselineRunId, overall, meanScore, dimensions, inputs, reportMarkdown,
                reportJson, added, labels, components, gate);
    }

    QualityReport withLabels(java.util.Map<String, String> runLabels) {
        return new QualityReport(runId, baselineRunId, overall, meanScore, dimensions, inputs, reportMarkdown,
                reportJson, notes, runLabels, components, gate);
    }

    QualityReport withComponents(List<Component> owned) {
        return new QualityReport(runId, baselineRunId, overall, meanScore, dimensions, inputs, reportMarkdown,
                reportJson, notes, labels, owned, gate);
    }

    QualityReport withGate(Gate verdict) {
        return new QualityReport(runId, baselineRunId, overall, meanScore, dimensions, inputs, reportMarkdown,
                reportJson, notes, labels, components, verdict);
    }
}
