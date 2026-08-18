package io.testforge.db.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.artifact.ArtifactSink;
import io.testforge.artifact.TestArtifact;
import io.testforge.db.contract.diff.DbChange;
import io.testforge.db.contract.diff.DbSchemaComparator;
import io.testforge.db.contract.model.DbSchemaSnapshot;
import io.testforge.db.contract.policy.DbChangeAssessment;
import io.testforge.db.contract.policy.DbCompatibility;
import io.testforge.db.contract.policy.DbCompatibilityPolicy;
import io.testforge.db.contract.snapshot.DbSchemaInspector;
import io.testforge.db.contract.snapshot.DbSchemaSnapshotStore;
import io.testforge.db.datasource.DataSourceRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the database contract check end to end: inspect, snapshot, diff,
 * classify, report, gate.
 *
 * <p>The pipeline is deliberately one-directional. Capturing never overwrites
 * the baseline on its own — promoting a new baseline is an explicit call to
 * {@link #writeSnapshot(Path)}, so a schema change can never make itself
 * disappear by being re-recorded.
 *
 * <pre>{@code
 * // in a scheduled or review CI job
 * DbContractReport report = dbContractRunner.assertCompatible();
 * }</pre>
 */
public class DbContractRunner {

    private static final Logger log = LoggerFactory.getLogger(DbContractRunner.class);

    private static final String SOURCE = "module-db-contract";

    private final DataSourceRegistry registry;
    private final DbSchemaInspector inspector;
    private final DbSchemaSnapshotStore snapshotStore;
    private final DbCompatibilityPolicy policy;
    private final DbContractProperties properties;
    private final ObjectMapper objectMapper;
    private final ArtifactSink artifactSink;

    public DbContractRunner(
            DataSourceRegistry registry,
            DbSchemaInspector inspector,
            DbSchemaSnapshotStore snapshotStore,
            DbCompatibilityPolicy policy,
            DbContractProperties properties,
            ObjectMapper objectMapper,
            ArtifactSink artifactSink) {
        this.registry = registry;
        this.inspector = inspector;
        this.snapshotStore = snapshotStore;
        this.policy = policy;
        this.properties = properties;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.artifactSink = artifactSink == null ? ArtifactSink.NO_OP : artifactSink;
    }

    /**
     * Inspects the configured schema and returns its snapshot without writing
     * anything.
     *
     * @return the current schema snapshot
     */
    public DbSchemaSnapshot capture() {
        return inspector.inspect(dataSource(), properties.schema());
    }

    /**
     * Captures the current schema and writes it to the given file. This is how a
     * baseline is promoted — deliberately an explicit call, never a side effect
     * of running the check.
     *
     * @param file the file to write the snapshot to
     * @return the captured snapshot
     */
    public DbSchemaSnapshot writeSnapshot(Path file) {
        DbSchemaSnapshot snapshot = capture();
        snapshotStore.write(file, snapshot);
        return snapshot;
    }

    /**
     * Captures the current schema and promotes it to the configured baseline.
     * Separate from {@link #run()} on purpose: a schema change must never be
     * able to erase the evidence of itself by being re-recorded.
     *
     * @return the captured snapshot
     */
    public DbSchemaSnapshot writeBaseline() {
        return writeSnapshot(Path.of(properties.baselineFile()));
    }

    /**
     * Runs the full check and writes {@code report.json} and {@code report.md}.
     * Never throws on an incompatible schema — use {@link #assertCompatible()}
     * for the CI gate.
     *
     * @return the report
     */
    public DbContractReport run() {
        Path outputDir = Path.of(properties.outputDir());
        Path currentSnapshotFile = outputDir.resolve("schema-snapshot.json");
        Path baselineFile = Path.of(properties.baselineFile());
        Path reportJson = outputDir.resolve("report.json");
        Path reportMarkdown = outputDir.resolve("report.md");

        if (!Boolean.TRUE.equals(properties.enabled())) {
            DbContractReport disabled = new DbContractReport(false, Instant.now().toString(),
                    properties.schema(), false, true, DbCompatibility.NON_BREAKING,
                    0, 0, 0, 0, List.of(), baselineFile.toString(), currentSnapshotFile.toString(),
                    reportJson.toString(), reportMarkdown.toString());
            // A disabled check passes, by design. Say so out loud: a green gate and
            // a gate that never ran are indistinguishable to the caller, and a
            // misspelled property should not read as "the schema is fine".
            log.warn("Database contract check is disabled (forge.db-contract.enabled=false); "
                    + "no schema was inspected and no comparison was made.");
            writeReports(disabled, reportJson, reportMarkdown);
            return disabled;
        }

        DbSchemaSnapshot current = capture();
        snapshotStore.write(currentSnapshotFile, current);

        boolean baselinePresent = Files.exists(baselineFile);
        if (!baselinePresent) {
            // Same reasoning as the disabled path: passing because there is nothing
            // to compare against must not look like passing because nothing broke.
            log.warn("No baseline snapshot at {} — the database contract check passed without "
                    + "comparing anything. Promote the captured snapshot with writeBaseline() "
                    + "to start gating on it.", baselineFile);
        }
        List<DbChangeAssessment> assessments = baselinePresent
                ? assess(snapshotStore.read(baselineFile), current)
                : List.of();

        int breaking = count(assessments, DbCompatibility.BREAKING);
        int risky = count(assessments, DbCompatibility.RISKY);
        int unknown = count(assessments, DbCompatibility.UNKNOWN);
        int nonBreaking = count(assessments, DbCompatibility.NON_BREAKING);


        DbContractReport report = new DbContractReport(
                true,
                Instant.now().toString(),
                current.schema(),
                baselinePresent,
                passesGate(breaking, risky, unknown),
                worstClassified(assessments),
                breaking,
                risky,
                unknown,
                nonBreaking,
                assessments,
                baselineFile.toString(),
                currentSnapshotFile.toString(),
                reportJson.toString(),
                reportMarkdown.toString());

        writeReports(report, reportJson, reportMarkdown);
        registerArtifact(currentSnapshotFile, "schema-snapshot.json", "application/json", report);
        return report;
    }

    /**
     * Runs the check and fails when the configured gate is not met.
     *
     * @return the report, when it passes the gate
     * @throws DbContractException when it does not
     */
    public DbContractReport assertCompatible() {
        DbContractReport report = run();
        if (!report.compatible()) {
            throw new DbContractException(report);
        }
        return report;
    }

    private List<DbChangeAssessment> assess(DbSchemaSnapshot baseline, DbSchemaSnapshot current) {
        List<DbChange> changes = DbSchemaComparator.compare(baseline, current);
        List<DbChangeAssessment> assessments = new ArrayList<>(changes.size());
        for (DbChange change : changes) {
            assessments.add(policy.assess(change, baseline, current));
        }
        return List.copyOf(assessments);
    }

    /**
     * The most severe verdict on the NON_BREAKING/RISKY/BREAKING axis.
     * {@code UNKNOWN} means the change was not classified, so it is skipped
     * here and gated on its own instead of being ranked against the others.
     */
    private static DbCompatibility worstClassified(List<DbChangeAssessment> assessments) {
        DbCompatibility worst = DbCompatibility.NON_BREAKING;
        for (DbChangeAssessment assessment : assessments) {
            DbCompatibility verdict = assessment.compatibility();
            if (verdict.classified() && verdict.compareTo(worst) > 0) {
                worst = verdict;
            }
        }
        return worst;
    }

    /**
     * Each verdict has its own switch. Un-analysed changes are not "worse than
     * risky" or "better than risky" — a project decides separately whether an
     * unclassified change should stop the build.
     */
    private boolean passesGate(int breaking, int risky, int unknown) {
        DbContractProperties.FailOn failOn = properties.failOn();
        if (Boolean.TRUE.equals(failOn.breaking()) && breaking > 0) {
            return false;
        }
        if (Boolean.TRUE.equals(failOn.risky()) && risky > 0) {
            return false;
        }
        return !(Boolean.TRUE.equals(failOn.unknown()) && unknown > 0);
    }

    private static int count(List<DbChangeAssessment> assessments, DbCompatibility compatibility) {
        return (int) assessments.stream()
                .filter(assessment -> assessment.compatibility() == compatibility)
                .count();
    }

    private DataSource dataSource() {
        if (registry == null) {
            throw new IllegalStateException(
                    "No DataSourceRegistry is available. module-db auto-configuration registers one as soon as "
                            + "the application context has a DataSource bean.");
        }
        return registry.resolve(properties.datasource());
    }

    private void writeReports(DbContractReport report, Path reportJson, Path reportMarkdown) {
        writeString(reportJson, toJson(report));
        writeString(reportMarkdown, markdown(report));
        registerArtifact(reportJson, "report.json", "application/json", report);
        registerArtifact(reportMarkdown, "report.md", "text/markdown", report);
    }

    private String toJson(DbContractReport report) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to serialize the database contract report", e);
        }
    }

    String markdown(DbContractReport report) {
        StringBuilder out = new StringBuilder();
        out.append("# Database Contract Report\n\n");
        out.append("- enabled: ").append(report.enabled()).append('\n');
        out.append("- schema: ").append(report.schema()).append('\n');
        out.append("- generatedAt: ").append(report.generatedAt()).append('\n');
        out.append("- baseline: ").append(report.baselinePresent()
                ? report.baselineSnapshot()
                : "missing (nothing to compare against)").append('\n');
        out.append("- snapshot: ").append(report.currentSnapshot()).append('\n');
        out.append("- verdict: ").append(report.compatible() ? "PASS" : "FAIL")
                .append(" (worst classified: ").append(report.worstClassified())
                .append(", unclassified: ").append(report.unknownCount()).append(")\n");
        out.append("- changes: ").append(report.changes().size())
                .append(" (breaking ").append(report.breakingCount())
                .append(", risky ").append(report.riskyCount())
                .append(", unknown ").append(report.unknownCount())
                .append(", non-breaking ").append(report.nonBreakingCount())
                .append(")\n\n");

        if (!report.enabled()) {
            out.append("The check is disabled; set `forge.db-contract.enabled=true` to run it.\n");
            return out.toString();
        }
        if (!report.baselinePresent()) {
            out.append("No baseline snapshot was found, so no comparison was made. ")
                    .append("Promote the captured snapshot to `")
                    .append(report.baselineSnapshot())
                    .append("` to start gating on it.\n");
            return out.toString();
        }
        if (report.changes().isEmpty()) {
            out.append("No schema changes against the baseline.\n");
            return out.toString();
        }

        out.append("| Verdict | Change | Object | Before | After | Why |\n");
        out.append("| --- | --- | --- | --- | --- | --- |\n");
        for (DbChangeAssessment assessment : report.changes()) {
            DbChange change = assessment.change();
            out.append("| ").append(assessment.compatibility())
                    .append(" | ").append(change.type())
                    .append(" | `").append(change.path()).append('`')
                    .append(" | ").append(cell(change.before()))
                    .append(" | ").append(cell(change.after()))
                    .append(" | ").append(cell(assessment.reason()))
                    .append(" |\n");
        }
        return out.toString();
    }

    private static String cell(String value) {
        if (value == null || value.isBlank()) {
            return "—";
        }
        return value.replace("|", "\\|");
    }

    private void writeString(Path path, String value) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, value, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + path, e);
        }
    }

    /**
     * Publishing a diagnostic must never replace the failure that made the run
     * interesting, so registration failures are swallowed here as well as inside
     * the sink.
     */
    private void registerArtifact(Path file, String name, String mediaType, DbContractReport report) {
        try {
            artifactSink.register(new TestArtifact(
                    SOURCE,
                    "db-contract",
                    name,
                    file,
                    mediaType,
                    Instant.now(),
                    Map.of("schema", report.schema(),
                            "compatible", String.valueOf(report.compatible()),
                            "worstClassified", report.worstClassified().name())));
        } catch (RuntimeException e) {
            // best-effort by contract
        }
    }
}
