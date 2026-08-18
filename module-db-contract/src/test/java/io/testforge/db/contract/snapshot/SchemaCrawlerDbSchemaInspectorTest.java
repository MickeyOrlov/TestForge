package io.testforge.db.contract.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testforge.db.contract.model.DbColumn;
import io.testforge.db.contract.model.DbIndex;
import io.testforge.db.contract.model.DbSchemaSnapshot;
import io.testforge.db.contract.model.DbTable;
import io.testforge.db.schema.ColumnTypeFamily;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Proves the SchemaCrawler wrapper against a real (embedded) database. The
 * cross-vendor proof against PostgreSQL lives in {@code example-tests}.
 */
class SchemaCrawlerDbSchemaInspectorTest {

    private static DataSource dataSource;

    @BeforeAll
    static void createSchema() throws Exception {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:contract-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE customers (id BIGINT PRIMARY KEY, "
                    + "email VARCHAR(255) NOT NULL)");
            statement.execute("CREATE UNIQUE INDEX uq_customers_email ON customers(email)");
            statement.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, "
                    + "customer_id BIGINT NOT NULL, amount NUMERIC(10,2), "
                    + "status VARCHAR(32) DEFAULT 'new', "
                    + "CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers(id))");
            statement.execute("CREATE INDEX idx_orders_status ON orders(status)");
            statement.execute("CREATE VIEW recent_orders AS SELECT id FROM orders");
            statement.execute("CREATE TABLE flyway_schema_history (installed_rank INT PRIMARY KEY)");
        }
    }

    private DbSchemaSnapshot inspect() {
        return new SchemaCrawlerDbSchemaInspector().inspect(dataSource, "PUBLIC");
    }

    @Test
    void inspection_readsTablesColumnsKeysAndIndexes() {
        DbSchemaSnapshot snapshot = inspect();

        assertThat(snapshot.schema()).isEqualTo("PUBLIC");
        assertThat(snapshot.tables()).extracting(DbTable::name)
                .contains("CUSTOMERS", "ORDERS", "FLYWAY_SCHEMA_HISTORY");

        DbTable orders = snapshot.table("ORDERS").orElseThrow();
        assertThat(orders.columns()).extracting(DbColumn::name)
                .containsExactly("AMOUNT", "CUSTOMER_ID", "ID", "STATUS");
        assertThat(orders.primaryKey().columns()).containsExactly("ID");
        assertThat(orders.foreignKeys()).singleElement()
                .satisfies(foreignKey -> {
                    assertThat(foreignKey.name()).isEqualTo("FK_ORDERS_CUSTOMER");
                    assertThat(foreignKey.columns()).containsExactly("CUSTOMER_ID");
                    assertThat(foreignKey.referencedTable()).isEqualTo("CUSTOMERS");
                    assertThat(foreignKey.referencedColumns()).containsExactly("ID");
                });
        assertThat(orders.indexes()).extracting(DbIndex::name).contains("IDX_ORDERS_STATUS");
    }

    @Test
    void columns_carryTypeFamilyPhysicalTypeNullabilityAndDefault() {
        DbTable orders = inspect().table("ORDERS").orElseThrow();

        assertThat(orders.column("AMOUNT").orElseThrow())
                .satisfies(column -> {
                    assertThat(column.typeFamily()).isEqualTo(ColumnTypeFamily.DECIMAL);
                    assertThat(column.type()).contains("NUMERIC").contains("10");
                    assertThat(column.nullable()).isTrue();
                    assertThat(column.hasDefault()).isFalse();
                });
        assertThat(orders.column("STATUS").orElseThrow().hasDefault()).isTrue();
        assertThat(orders.column("CUSTOMER_ID").orElseThrow().nullable()).isFalse();
    }

    @Test
    void theIndexBackingThePrimaryKey_isNotReportedAsASeparateIndex() {
        DbTable orders = inspect().table("ORDERS").orElseThrow();

        assertThat(orders.indexes())
                .noneMatch(index -> index.unique() && index.columns().equals(orders.primaryKey().columns()));
    }

    @Test
    void uniqueIndexesThatAreNotThePrimaryKey_areKept() {
        DbTable customers = inspect().table("CUSTOMERS").orElseThrow();

        assertThat(customers.indexes())
                .filteredOn(DbIndex::unique)
                .extracting(DbIndex::name)
                .containsExactly("UQ_CUSTOMERS_EMAIL");
    }

    @Test
    void views_areNotPartOfTheContract() {
        assertThat(inspect().table("RECENT_ORDERS")).isEmpty();
    }

    @Test
    void excludeTables_dropsMigrationBookkeepingFromTheContract() {
        DbSchemaSnapshot snapshot = new SchemaCrawlerDbSchemaInspector(null, "flyway_.*")
                .inspect(dataSource, "PUBLIC");

        assertThat(snapshot.tables()).extracting(DbTable::name)
                .contains("ORDERS")
                .doesNotContain("FLYWAY_SCHEMA_HISTORY");
    }

    @Test
    void includeTables_narrowsTheContractToTheTablesUnderTest() {
        DbSchemaSnapshot snapshot = new SchemaCrawlerDbSchemaInspector("orders", null)
                .inspect(dataSource, "PUBLIC");

        assertThat(snapshot.tables()).extracting(DbTable::name).containsExactly("ORDERS");
    }

    @Test
    void repeatedInspections_produceEqualSnapshots() {
        assertThat(inspect()).isEqualTo(inspect());
    }

    @Test
    void anUnknownSchema_failsWithTheSchemasThatDoExist() {
        assertThatThrownBy(() -> new SchemaCrawlerDbSchemaInspector().inspect(dataSource, "no_such_schema"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no_such_schema")
                .hasMessageContaining("Visible schemas");
    }

    @Test
    void aBlankSchemaName_saysWhichPropertyToSet() {
        assertThatThrownBy(() -> new SchemaCrawlerDbSchemaInspector().inspect(dataSource, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forge.db-contract.schema");
    }
}
