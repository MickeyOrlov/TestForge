package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testforge.db.contract.DbContractException;
import io.testforge.db.contract.DbContractProperties;
import io.testforge.db.contract.DbContractReport;
import io.testforge.db.contract.DbContractRunner;
import io.testforge.db.contract.diff.DbChangeType;
import io.testforge.db.contract.model.DbSchemaSnapshot;
import io.testforge.db.contract.policy.DbChangeAssessment;
import io.testforge.db.contract.policy.DbCompatibility;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The database contract workflow end to end: capture a baseline, let the schema
 * move, and have CI say whether the move breaks consumers.
 *
 * <p>Runs offline against H2. {@code PostgresDbContractIT} proves the same
 * classifications against the vendor services actually run on.
 */
@SpringBootTest(properties = {
        "forge.db-contract.enabled=true",
        "forge.db-contract.schema=PUBLIC",
        // keep this example away from the JPA tables the rest of the suite uses
        "forge.db-contract.include-tables=contract_demo_.*",
        "forge.db-contract.output-dir=build/db-contract-example",
        "forge.db-contract.baseline-file=build/db-contract-example/baseline/schema-snapshot.json"
})
class DbContractExampleTest {

    @Autowired
    DbContractRunner dbContractRunner;

    @Autowired
    DbContractProperties properties;

    @Autowired
    DataSource dataSource;

    @BeforeEach
    void resetDemoSchema() throws Exception {
        execute("DROP TABLE IF EXISTS contract_demo_orders",
                "CREATE TABLE contract_demo_orders ("
                        + "id BIGINT PRIMARY KEY, "
                        + "status VARCHAR(32) NOT NULL, "
                        + "amount NUMERIC(10,2))");
        dbContractRunner.writeBaseline();
    }

    @Test
    void anUnchangedSchemaPasses_andTheSnapshotIsStableEnoughToCommit() {
        DbContractReport report = dbContractRunner.assertCompatible();

        assertThat(report.baselinePresent()).isTrue();
        assertThat(report.changes()).isEmpty();

        DbSchemaSnapshot snapshot = dbContractRunner.capture();
        assertThat(snapshot.table("CONTRACT_DEMO_ORDERS")).isPresent();
        assertThat(dbContractRunner.capture()).isEqualTo(snapshot);
    }

    @Test
    void addingANullableColumn_isNonBreakingAndTheBuildStaysGreen() throws Exception {
        execute("ALTER TABLE contract_demo_orders ADD COLUMN note VARCHAR(255)");

        DbContractReport report = dbContractRunner.assertCompatible();

        assertThat(report.changes()).singleElement()
                .satisfies(assessment -> {
                    assertThat(assessment.change().type()).isEqualTo(DbChangeType.COLUMN_ADDED);
                    assertThat(assessment.compatibility()).isEqualTo(DbCompatibility.NON_BREAKING);
                });
    }

    @Test
    void droppingAColumn_breaksTheBuildAndTheFailureNamesTheColumn() throws Exception {
        execute("ALTER TABLE contract_demo_orders DROP COLUMN amount");

        assertThatThrownBy(() -> dbContractRunner.assertCompatible())
                .isInstanceOf(DbContractException.class)
                .hasMessageContaining("COLUMN_REMOVED")
                .hasMessageContaining("CONTRACT_DEMO_ORDERS.AMOUNT");
    }

    @Test
    void aBreakingRunLeavesAReadableReportBehind() throws Exception {
        execute("ALTER TABLE contract_demo_orders DROP COLUMN amount");

        DbContractReport report = dbContractRunner.run();

        assertThat(report.compatible()).isFalse();
        assertThat(report.worstClassified()).isEqualTo(DbCompatibility.BREAKING);
        assertThat(Files.readString(Path.of(report.reportMarkdown())))
                .contains("# Database Contract Report")
                .contains("BREAKING")
                .contains("`CONTRACT_DEMO_ORDERS.AMOUNT`");
        assertThat(Path.of(report.reportJson())).exists();
        assertThat(Path.of(report.currentSnapshot())).exists();
    }

    @Test
    void aRiskyChangeIsReportedButDoesNotFailUntilTheProjectOptsIn() throws Exception {
        execute("ALTER TABLE contract_demo_orders ALTER COLUMN status SET DATA TYPE VARCHAR(8)");

        DbContractReport report = dbContractRunner.assertCompatible();

        assertThat(report.changesWith(DbCompatibility.RISKY))
                .extracting(assessment -> assessment.change().type())
                .contains(DbChangeType.COLUMN_PHYSICAL_TYPE_CHANGED);
        assertThat(report.compatible()).isTrue();
    }

    @Test
    void relaxingNotNull_isReportedAsRiskyRatherThanWavedThrough() throws Exception {
        execute("ALTER TABLE contract_demo_orders ALTER COLUMN status SET NULL");

        DbContractReport report = dbContractRunner.assertCompatible();

        assertThat(report.changesWith(DbCompatibility.RISKY))
                .extracting(assessment -> assessment.change().type())
                .contains(DbChangeType.COLUMN_NULLABILITY_RELAXED);
    }

    @Test
    void everyReportedChangeExplainsItself() throws Exception {
        execute("ALTER TABLE contract_demo_orders DROP COLUMN amount",
                "ALTER TABLE contract_demo_orders ADD COLUMN tenant VARCHAR(32) NOT NULL DEFAULT 'default'");

        DbContractReport report = dbContractRunner.run();

        assertThat(report.changes()).isNotEmpty();
        assertThat(report.changes()).extracting(DbChangeAssessment::reason).allSatisfy(
                reason -> assertThat(reason).isNotBlank());
    }

    @Test
    void theBaselineIsOnlyEverReplacedOnPurpose() throws Exception {
        Path baseline = Path.of(properties.baselineFile());
        execute("ALTER TABLE contract_demo_orders DROP COLUMN amount");

        dbContractRunner.run();
        assertThat(dbContractRunner.run().breakingCount())
                .as("running the check must not quietly adopt the new schema")
                .isEqualTo(1);

        dbContractRunner.writeBaseline();
        assertThat(baseline).exists();
        assertThat(dbContractRunner.run().changes()).isEmpty();
    }

    private void execute(String... statements) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }
}
