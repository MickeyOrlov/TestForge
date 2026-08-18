package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testforge.db.contract.DbContractException;
import io.testforge.db.contract.DbContractReport;
import io.testforge.db.contract.DbContractRunner;
import io.testforge.db.contract.diff.DbChangeType;
import io.testforge.db.contract.model.DbSchemaSnapshot;
import io.testforge.db.contract.model.DbTable;
import io.testforge.db.contract.policy.DbChangeAssessment;
import io.testforge.db.contract.policy.DbCompatibility;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The database contract check against the vendor services actually run on.
 * H2 proves the logic in {@code DbContractExampleTest}; this one proves that
 * PostgreSQL's own metadata — its type names, its constraint names, the index
 * it creates behind every primary key — lands in the model the way the policy
 * expects.
 *
 * <p>Tagged {@code containers} and excluded from the default build — run with
 * {@code ./gradlew :example-tests:containersTest} when Docker is available.
 */
@SpringBootTest(properties = {
        "forge.db-contract.enabled=true",
        "forge.db-contract.schema=public",
        "forge.db-contract.include-tables=contract_demo_.*",
        "forge.db-contract.output-dir=build/db-contract-postgres",
        "forge.db-contract.baseline-file=build/db-contract-postgres/baseline/schema-snapshot.json"
})
@Tag("containers")
@Testcontainers
class PostgresDbContractIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    DbContractRunner dbContractRunner;

    @Autowired
    DataSource dataSource;

    @BeforeEach
    void resetDemoSchema() throws Exception {
        execute("DROP TABLE IF EXISTS contract_demo_orders",
                "DROP TABLE IF EXISTS contract_demo_customers",
                "CREATE TABLE contract_demo_customers ("
                        + "id BIGINT PRIMARY KEY, "
                        + "email VARCHAR(255) NOT NULL)",
                "CREATE TABLE contract_demo_orders ("
                        + "id BIGINT PRIMARY KEY, "
                        + "customer_id BIGINT NOT NULL, "
                        + "status VARCHAR(32) NOT NULL, "
                        + "amount NUMERIC(10,2), "
                        + "note VARCHAR(255))");
        dbContractRunner.writeBaseline();
    }

    @Test
    void postgresMetadataLandsInTheNormalizedModel() {
        DbSchemaSnapshot snapshot = dbContractRunner.capture();

        assertThat(snapshot.schema()).isEqualTo("public");
        assertThat(snapshot.tables()).extracting(DbTable::name)
                .containsExactly("contract_demo_customers", "contract_demo_orders");

        DbTable orders = snapshot.table("contract_demo_orders").orElseThrow();
        assertThat(orders.columns()).extracting(column -> column.name() + ":" + column.typeFamily())
                .contains("amount:DECIMAL", "customer_id:INTEGER", "id:INTEGER", "status:CHARACTER");
        assertThat(orders.primaryKey().columns()).containsExactly("id");
        assertThat(orders.indexes())
                .as("the index PostgreSQL creates behind the primary key is not a separate contract element")
                .noneMatch(index -> index.columns().equals(List.of("id")) && index.unique());
    }

    @Test
    void repeatedCapturesOfAnUnchangedPostgresSchema_areIdentical() {
        assertThat(dbContractRunner.capture()).isEqualTo(dbContractRunner.capture());
        assertThat(dbContractRunner.assertCompatible().changes()).isEmpty();
    }

    @Test
    void addNullableColumn_isNonBreaking() throws Exception {
        execute("ALTER TABLE contract_demo_orders ADD COLUMN shipped_at TIMESTAMP");

        assertThat(only("contract_demo_orders.shipped_at"))
                .satisfies(assessment -> {
                    assertThat(assessment.change().type()).isEqualTo(DbChangeType.COLUMN_ADDED);
                    assertThat(assessment.compatibility()).isEqualTo(DbCompatibility.NON_BREAKING);
                });
    }

    @Test
    void addNotNullColumnWithoutDefault_isBreaking_withDefaultItIsOnlyRisky() throws Exception {
        execute("ALTER TABLE contract_demo_orders ADD COLUMN tenant VARCHAR(32) NOT NULL");
        assertThat(only("contract_demo_orders.tenant").compatibility()).isEqualTo(DbCompatibility.BREAKING);

        execute("ALTER TABLE contract_demo_orders DROP COLUMN tenant",
                "ALTER TABLE contract_demo_orders ADD COLUMN tenant VARCHAR(32) NOT NULL DEFAULT 'default'");
        assertThat(only("contract_demo_orders.tenant").compatibility()).isEqualTo(DbCompatibility.RISKY);
    }

    @Test
    void dropColumn_isBreakingAndFailsTheGate() throws Exception {
        execute("ALTER TABLE contract_demo_orders DROP COLUMN note");

        assertThat(only("contract_demo_orders.note"))
                .satisfies(assessment -> {
                    assertThat(assessment.change().type()).isEqualTo(DbChangeType.COLUMN_REMOVED);
                    assertThat(assessment.compatibility()).isEqualTo(DbCompatibility.BREAKING);
                });
        assertThatThrownBy(() -> dbContractRunner.assertCompatible())
                .isInstanceOf(DbContractException.class);
    }

    @Test
    void typeFamilyChange_isBreaking() throws Exception {
        execute("ALTER TABLE contract_demo_orders "
                + "ALTER COLUMN amount TYPE VARCHAR(32) USING amount::VARCHAR");

        DbChangeAssessment assessment = only("contract_demo_orders.amount");

        assertThat(assessment.change().type()).isEqualTo(DbChangeType.COLUMN_TYPE_FAMILY_CHANGED);
        assertThat(assessment.change().before()).contains("DECIMAL");
        assertThat(assessment.change().after()).contains("CHARACTER");
        assertThat(assessment.compatibility()).isEqualTo(DbCompatibility.BREAKING);
    }

    @Test
    void droppingNotNull_isRisky_andAddingItBackIsBreaking() throws Exception {
        execute("ALTER TABLE contract_demo_orders ALTER COLUMN status DROP NOT NULL");
        assertThat(only("contract_demo_orders.status"))
                .satisfies(assessment -> {
                    assertThat(assessment.change().type()).isEqualTo(DbChangeType.COLUMN_NULLABILITY_RELAXED);
                    assertThat(assessment.compatibility())
                            .as("readers lose the guarantee that this column always has a value")
                            .isEqualTo(DbCompatibility.RISKY);
                });

        dbContractRunner.writeBaseline();
        execute("ALTER TABLE contract_demo_orders ALTER COLUMN status SET NOT NULL");
        assertThat(only("contract_demo_orders.status"))
                .satisfies(assessment -> {
                    assertThat(assessment.change().type()).isEqualTo(DbChangeType.COLUMN_NULLABILITY_TIGHTENED);
                    assertThat(assessment.compatibility()).isEqualTo(DbCompatibility.BREAKING);
                });
    }

    @Test
    void addingAndDroppingAForeignKey_areBothClassified() throws Exception {
        execute("ALTER TABLE contract_demo_orders ADD CONSTRAINT fk_demo_orders_customer "
                + "FOREIGN KEY (customer_id) REFERENCES contract_demo_customers(id)");

        DbChangeAssessment added = only("contract_demo_orders.fk_demo_orders_customer");
        assertThat(added.change().type()).isEqualTo(DbChangeType.FOREIGN_KEY_ADDED);
        assertThat(added.change().after()).isEqualTo("(customer_id) -> contract_demo_customers(id)");
        assertThat(added.compatibility()).isEqualTo(DbCompatibility.RISKY);

        dbContractRunner.writeBaseline();
        execute("ALTER TABLE contract_demo_orders DROP CONSTRAINT fk_demo_orders_customer");

        DbChangeAssessment dropped = only("contract_demo_orders.fk_demo_orders_customer");
        assertThat(dropped.change().type()).isEqualTo(DbChangeType.FOREIGN_KEY_REMOVED);
        assertThat(dropped.compatibility()).isEqualTo(DbCompatibility.RISKY);
    }

    @Test
    void addingAndDroppingAnIndex_areBothClassified() throws Exception {
        execute("CREATE INDEX idx_demo_orders_status ON contract_demo_orders(status)");

        DbChangeAssessment added = only("contract_demo_orders.idx_demo_orders_status");
        assertThat(added.change().type()).isEqualTo(DbChangeType.INDEX_ADDED);
        assertThat(added.compatibility()).isEqualTo(DbCompatibility.NON_BREAKING);

        dbContractRunner.writeBaseline();
        execute("DROP INDEX idx_demo_orders_status");

        DbChangeAssessment dropped = only("contract_demo_orders.idx_demo_orders_status");
        assertThat(dropped.change().type()).isEqualTo(DbChangeType.INDEX_REMOVED);
        assertThat(dropped.compatibility()).isEqualTo(DbCompatibility.RISKY);
    }

    @Test
    void aUniqueIndex_isRiskyToAddAndRiskyToLose() throws Exception {
        execute("CREATE UNIQUE INDEX uq_demo_customers_email ON contract_demo_customers(email)");

        assertThat(only("contract_demo_customers.uq_demo_customers_email").compatibility())
                .isEqualTo(DbCompatibility.RISKY);

        dbContractRunner.writeBaseline();
        execute("DROP INDEX uq_demo_customers_email");

        DbChangeAssessment dropped = only("contract_demo_customers.uq_demo_customers_email");
        assertThat(dropped.compatibility()).isEqualTo(DbCompatibility.RISKY);
        assertThat(dropped.reason()).contains("uniqueness");
    }

    @Test
    void droppingAWholeTable_isReportedOnceAsBreaking() throws Exception {
        execute("DROP TABLE contract_demo_orders");

        DbContractReport report = dbContractRunner.run();

        assertThat(report.compatible()).isFalse();
        assertThat(report.changes()).singleElement()
                .satisfies(assessment -> {
                    assertThat(assessment.change().type()).isEqualTo(DbChangeType.TABLE_REMOVED);
                    assertThat(assessment.change().table()).isEqualTo("contract_demo_orders");
                    assertThat(assessment.compatibility()).isEqualTo(DbCompatibility.BREAKING);
                });
    }

    private DbChangeAssessment only(String path) {
        List<DbChangeAssessment> changes = dbContractRunner.run().changes();
        assertThat(changes).extracting(assessment -> assessment.change().path()).containsExactly(path);
        return changes.get(0);
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
