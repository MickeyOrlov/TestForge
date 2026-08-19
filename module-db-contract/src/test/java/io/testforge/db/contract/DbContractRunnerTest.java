package io.testforge.db.contract;

import static io.testforge.db.contract.TestSchemas.column;
import static io.testforge.db.contract.TestSchemas.id;
import static io.testforge.db.contract.TestSchemas.schema;
import static io.testforge.db.contract.TestSchemas.table;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.artifact.ArtifactSink;
import io.testforge.artifact.TestArtifact;
import io.testforge.db.contract.diff.DbChangeType;
import io.testforge.db.contract.model.DbSchemaSnapshot;
import io.testforge.db.contract.policy.DbChangeAssessment;
import io.testforge.db.contract.policy.DbCompatibility;
import io.testforge.db.contract.policy.DefaultDbCompatibilityPolicy;
import io.testforge.db.contract.snapshot.DbSchemaInspector;
import io.testforge.db.contract.snapshot.DbSchemaSnapshotStore;
import io.testforge.db.datasource.DataSourceRegistry;
import io.testforge.db.schema.ColumnTypeFamily;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DbContractRunnerTest {

    private static final DbSchemaSnapshot BASELINE = schema(table("orders", List.of(
            id(),
            column("status", ColumnTypeFamily.CHARACTER, "varchar(32)", true))));

    private final DbSchemaSnapshotStore store = new DbSchemaSnapshotStore();
    private final List<TestArtifact> registered = new ArrayList<>();

    private final ArtifactSink recordingSink = new ArtifactSink() {
        @Override
        public Path directoryFor(String source) {
            return Path.of(System.getProperty("java.io.tmpdir"));
        }

        @Override
        public void register(TestArtifact artifact) {
            registered.add(artifact);
        }

        @Override
        public TestArtifact write(String source, String category, String name, String mediaType, String content) {
            throw new UnsupportedOperationException("not used");
        }
    };

    /** No connection is ever opened: the stub inspector never touches the datasource. */
    private static final DataSourceRegistry REGISTRY = new DataSourceRegistry(
            java.util.Map.of("testDataSource", new DriverManagerDataSource("jdbc:h2:mem:unused")),
            "testDataSource");

    private DbContractRunner runner(Path dir, DbSchemaSnapshot current, DbContractProperties properties) {
        DbSchemaInspector inspector = (dataSource, schemaName) -> current;
        return new DbContractRunner(REGISTRY, inspector, store, new DefaultDbCompatibilityPolicy(),
                properties, new ObjectMapper(), recordingSink);
    }

    private DbContractProperties properties(Path dir, Boolean failOnRisky) {
        return properties(dir, failOnRisky, null);
    }

    private DbContractProperties properties(Path dir, Boolean failOnRisky, Boolean failOnUnknown) {
        return new DbContractProperties(true, null, "public",
                dir.resolve("baseline/schema-snapshot.json").toString(),
                dir.resolve("out").toString(), null, null,
                new DbContractProperties.FailOn(null, failOnRisky, failOnUnknown));
    }

    /** A vendor type TestForge does not map, on both sides, so the policy declines to judge. */
    private static DbSchemaSnapshot withUnmappedTypeChange(String type) {
        return schema(table("orders", List.of(
                id(),
                column("status", ColumnTypeFamily.CHARACTER, "varchar(32)", true),
                column("payload", ColumnTypeFamily.UNKNOWN, type, true))));
    }

    private Path writeBaseline(Path dir, DbSchemaSnapshot baseline) {
        return store.write(dir.resolve("baseline/schema-snapshot.json"), baseline);
    }

    @Test
    void unchangedSchema_passesAndReportsNoChanges(@TempDir Path dir) {
        writeBaseline(dir, BASELINE);

        DbContractReport report = runner(dir, BASELINE, properties(dir, null)).run();

        assertThat(report.compatible()).isTrue();
        assertThat(report.baselinePresent()).isTrue();
        assertThat(report.changes()).isEmpty();
        assertThat(report.worstClassified()).isEqualTo(DbCompatibility.NON_BREAKING);
    }

    @Test
    void missingBaseline_reportsThatNothingWasComparedInsteadOfPassingSilently(@TempDir Path dir) {
        DbContractReport report = runner(dir, BASELINE, properties(dir, null)).run();

        assertThat(report.baselinePresent()).isFalse();
        assertThat(report.compatible()).isTrue();
        assertThat(Files.exists(Path.of(report.reportMarkdown()))).isTrue();
        assertThat(readString(Path.of(report.reportMarkdown()))).contains("No baseline snapshot was found");
    }

    @Test
    void anEnabledCheckWithNoBaseline_failsTheGateClosed(@TempDir Path dir) {
        DbContractRunner runner = runner(dir, BASELINE, properties(dir, null));

        assertThatThrownBy(runner::assertCompatible)
                .as("a pipeline must not believe it is gated while every run compares nothing")
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(DbContractException.class)
                .hasMessageContaining("no baseline snapshot")
                .hasMessageContaining("writeBaseline()");
    }

    @Test
    void aMissingBaselineIsNotReportedAsASchemaVerdict(@TempDir Path dir) {
        DbContractReport report = runner(dir, BASELINE, properties(dir, null)).run();

        // nothing was compared, so there is nothing to call breaking, risky or unknown
        assertThat(report.changes()).isEmpty();
        assertThat(report.breakingCount()).isZero();
        assertThat(report.riskyCount()).isZero();
        assertThat(report.unknownCount()).isZero();
        assertThat(report.worstClassified()).isEqualTo(DbCompatibility.NON_BREAKING);
    }

    @Test
    void runWithoutABaseline_stillCapturesAndReportsForBootstrap(@TempDir Path dir) {
        DbContractRunner runner = runner(dir, BASELINE, properties(dir, null));

        DbContractReport report = runner.run();

        assertThat(report.baselinePresent()).isFalse();
        assertThat(Path.of(report.currentSnapshot())).exists();
        assertThat(Path.of(report.reportJson())).exists();
        assertThat(readString(Path.of(report.reportMarkdown()))).contains("No baseline snapshot was found");

        // and promoting that capture makes the gate usable, without any config change
        runner.writeBaseline();
        assertThat(runner.assertCompatible().changes()).isEmpty();
    }

    @Test
    void aDisabledCheckStillPassesAndStillTouchesNoDatabase(@TempDir Path dir) {
        DbSchemaInspector exploding = (dataSource, schemaName) -> {
            throw new AssertionError("a disabled contract check must not connect to a database");
        };
        DbContractProperties disabled = new DbContractProperties(false, null, "public",
                dir.resolve("absent-baseline.json").toString(), dir.resolve("out").toString(),
                null, null, null);

        DbContractReport report = new DbContractRunner(REGISTRY, exploding, store,
                new DefaultDbCompatibilityPolicy(), disabled, new ObjectMapper(), ArtifactSink.NO_OP)
                .assertCompatible();

        assertThat(report.enabled()).isFalse();
        assertThat(report.baselinePresent()).isFalse();
    }

    @Test
    void aDroppedColumn_failsTheDefaultGateAndNamesTheChange(@TempDir Path dir) {
        writeBaseline(dir, BASELINE);
        DbSchemaSnapshot current = schema(table("orders", List.of(id())));

        DbContractRunner runner = runner(dir, current, properties(dir, null));
        DbContractReport report = runner.run();

        assertThat(report.compatible()).isFalse();
        assertThat(report.breakingCount()).isEqualTo(1);
        assertThat(report.worstClassified()).isEqualTo(DbCompatibility.BREAKING);
        assertThat(report.changes()).singleElement()
                .extracting(assessment -> assessment.change().type())
                .isEqualTo(DbChangeType.COLUMN_REMOVED);

        assertThatThrownBy(runner::assertCompatible)
                .isInstanceOf(DbContractException.class)
                .hasMessageContaining("COLUMN_REMOVED orders.status")
                .hasMessageContaining("1 breaking");
    }

    @Test
    void riskyChangesPassUntilAProjectOptsIn(@TempDir Path dir) {
        writeBaseline(dir, BASELINE);
        DbSchemaSnapshot current = schema(table("orders", List.of(
                id(), column("status", ColumnTypeFamily.CHARACTER, "varchar(8)", true))));

        assertThat(runner(dir, current, properties(dir, null)).run().compatible()).isTrue();
        assertThat(runner(dir, current, properties(dir, true)).run().compatible()).isFalse();
    }

    @Test
    void theUnknownGateIsIndependentOfTheRiskyGate(@TempDir Path dir) {
        writeBaseline(dir, withUnmappedTypeChange("json"));
        DbSchemaSnapshot current = withUnmappedTypeChange("jsonb");

        DbContractReport riskyGateOnly = runner(dir, current, properties(dir, true, false)).run();
        assertThat(riskyGateOnly.unknownCount()).isEqualTo(1);
        assertThat(riskyGateOnly.compatible())
                .as("gating on risky changes must not gate on unclassified ones")
                .isTrue();

        DbContractReport unknownGate = runner(dir, current, properties(dir, false, true)).run();
        assertThat(unknownGate.compatible()).isFalse();
    }

    @Test
    void theRiskyGateIsIndependentOfTheUnknownGate(@TempDir Path dir) {
        writeBaseline(dir, BASELINE);
        DbSchemaSnapshot current = schema(table("orders", List.of(
                id(), column("status", ColumnTypeFamily.CHARACTER, "varchar(8)", true))));

        DbContractReport unknownGateOnly = runner(dir, current, properties(dir, false, true)).run();

        assertThat(unknownGateOnly.riskyCount()).isEqualTo(1);
        assertThat(unknownGateOnly.compatible())
                .as("gating on unclassified changes must not gate on risky ones")
                .isTrue();
    }

    @Test
    void anUnclassifiedChange_doesNotInflateTheWorstClassifiedVerdict(@TempDir Path dir) {
        writeBaseline(dir, withUnmappedTypeChange("json"));

        DbContractReport unknownOnly = runner(dir, withUnmappedTypeChange("jsonb"),
                properties(dir, null)).run();
        assertThat(unknownOnly.unknownCount()).isEqualTo(1);
        assertThat(unknownOnly.worstClassified())
                .as("UNKNOWN is not a severity, so it cannot outrank NON_BREAKING")
                .isEqualTo(DbCompatibility.NON_BREAKING);

        DbSchemaSnapshot riskyAndUnknown = schema(table("orders", List.of(
                id(),
                column("status", ColumnTypeFamily.CHARACTER, "varchar(8)", true),
                column("payload", ColumnTypeFamily.UNKNOWN, "jsonb", true))));
        DbContractReport mixed = runner(dir, riskyAndUnknown, properties(dir, null)).run();

        assertThat(mixed.unknownCount()).isEqualTo(1);
        assertThat(mixed.riskyCount()).isEqualTo(1);
        assertThat(mixed.worstClassified()).isEqualTo(DbCompatibility.RISKY);
    }

    @Test
    void theReportSeparatesTheSeverityAxisFromTheUnclassifiedCount(@TempDir Path dir) {
        writeBaseline(dir, withUnmappedTypeChange("json"));

        DbContractReport report = runner(dir, withUnmappedTypeChange("jsonb"),
                properties(dir, null)).run();

        assertThat(readString(Path.of(report.reportMarkdown())))
                .contains("worst classified: NON_BREAKING")
                .contains("unclassified: 1");
    }

    @Test
    void relaxingNotNull_isRiskyAndPassesTheDefaultGate(@TempDir Path dir) {
        DbSchemaSnapshot notNull = schema(table("orders", List.of(
                id(), column("status", ColumnTypeFamily.CHARACTER, "varchar(32)", false))));
        writeBaseline(dir, notNull);

        DbContractReport report = runner(dir, BASELINE, properties(dir, null)).run();

        assertThat(report.changes()).singleElement()
                .satisfies(assessment -> {
                    assertThat(assessment.change().type())
                            .isEqualTo(io.testforge.db.contract.diff.DbChangeType.COLUMN_NULLABILITY_RELAXED);
                    assertThat(assessment.compatibility()).isEqualTo(DbCompatibility.RISKY);
                });
        assertThat(report.riskyCount()).isEqualTo(1);
        assertThat(report.compatible()).isTrue();
        assertThat(runner(dir, BASELINE, properties(dir, true)).run().compatible()).isFalse();
    }

    @Test
    void report_writesJsonMarkdownAndTheCapturedSnapshot(@TempDir Path dir) {
        writeBaseline(dir, BASELINE);
        DbSchemaSnapshot current = schema(table("orders", List.of(id())));

        DbContractReport report = runner(dir, current, properties(dir, null)).run();

        assertThat(Path.of(report.currentSnapshot())).exists();
        assertThat(Path.of(report.reportJson())).exists();
        assertThat(readString(Path.of(report.reportMarkdown())))
                .contains("# Database Contract Report")
                .contains("BREAKING")
                .contains("`orders.status`")
                .contains("verdict: FAIL");
    }

    @Test
    void reportJson_isReadableBackAsAReport(@TempDir Path dir) throws Exception {
        writeBaseline(dir, BASELINE);
        DbSchemaSnapshot current = schema(table("orders", List.of(id())));

        DbContractReport report = runner(dir, current, properties(dir, null)).run();
        DbContractReport parsed = new ObjectMapper()
                .readValue(Path.of(report.reportJson()).toFile(), DbContractReport.class);

        assertThat(parsed.breakingCount()).isEqualTo(1);
        assertThat(parsed.changes()).extracting(DbChangeAssessment::compatibility)
                .containsExactly(DbCompatibility.BREAKING);
    }

    @Test
    void reportsAreRegisteredWithTheArtifactSink(@TempDir Path dir) {
        writeBaseline(dir, BASELINE);

        runner(dir, BASELINE, properties(dir, null)).run();

        assertThat(registered).extracting(TestArtifact::name)
                .contains("report.json", "report.md", "schema-snapshot.json");
        assertThat(registered).allSatisfy(artifact ->
                assertThat(artifact.source()).isEqualTo("module-db-contract"));
    }

    @Test
    void runningWhileDisabled_touchesNoDatabaseAndSaysSoInTheReport(@TempDir Path dir) {
        DbSchemaInspector exploding = (dataSource, schemaName) -> {
            throw new AssertionError("a disabled contract check must not connect to a database");
        };
        DbContractProperties disabled = new DbContractProperties(false, null, "public",
                dir.resolve("baseline.json").toString(), dir.resolve("out").toString(), null, null, null);

        DbContractReport report = new DbContractRunner(REGISTRY, exploding, store,
                new DefaultDbCompatibilityPolicy(), disabled, new ObjectMapper(), ArtifactSink.NO_OP).run();

        assertThat(report.enabled()).isFalse();
        assertThat(report.compatible()).isTrue();
        assertThat(readString(Path.of(report.reportMarkdown()))).contains("forge.db-contract.enabled=true");
    }

    @Test
    void writeSnapshot_isTheOnlyWayABaselineIsEverReplaced(@TempDir Path dir) {
        Path baseline = writeBaseline(dir, BASELINE);
        DbSchemaSnapshot current = schema(table("orders", List.of(id())));
        DbContractRunner runner = runner(dir, current, properties(dir, null));

        runner.run();
        assertThat(store.read(baseline)).isEqualTo(BASELINE);

        runner.writeSnapshot(baseline);
        assertThat(store.read(baseline)).isEqualTo(current);
    }

    @Test
    void writeBaseline_promotesTheCurrentSchemaToTheConfiguredBaselinePath(@TempDir Path dir) {
        DbSchemaSnapshot current = schema(table("orders", List.of(id())));
        DbContractProperties properties = properties(dir, null);

        runner(dir, current, properties).writeBaseline();

        assertThat(store.read(Path.of(properties.baselineFile()))).isEqualTo(current);
    }

    @Test
    void aFailingArtifactSink_neverReplacesTheContractResult(@TempDir Path dir) {
        writeBaseline(dir, BASELINE);
        ArtifactSink broken = new ArtifactSink() {
            @Override
            public Path directoryFor(String source) {
                throw new IllegalStateException("sink is broken");
            }

            @Override
            public void register(TestArtifact artifact) {
                throw new IllegalStateException("sink is broken");
            }

            @Override
            public TestArtifact write(String source, String category, String name, String mediaType, String content) {
                throw new IllegalStateException("sink is broken");
            }
        };
        DbSchemaSnapshot current = schema(table("orders", List.of(id())));

        DbContractReport report = new DbContractRunner(REGISTRY, (ds, s) -> current, store,
                new DefaultDbCompatibilityPolicy(), properties(dir, null), new ObjectMapper(), broken).run();

        assertThat(report.breakingCount()).isEqualTo(1);
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
