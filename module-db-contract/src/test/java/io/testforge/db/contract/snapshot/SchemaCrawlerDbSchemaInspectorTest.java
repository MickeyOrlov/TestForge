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
import org.junit.jupiter.api.io.TempDir;
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

            // a second schema holding a SAME-NAMED table, plus a key pointing at it
            statement.execute("CREATE SCHEMA archive");
            statement.execute("CREATE TABLE archive.customers (id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE cross_schema_orders (id BIGINT PRIMARY KEY, "
                    + "local_customer_id BIGINT, archived_customer_id BIGINT, "
                    + "CONSTRAINT fk_local FOREIGN KEY (local_customer_id) REFERENCES customers(id), "
                    + "CONSTRAINT fk_archived FOREIGN KEY (archived_customer_id) "
                    + "REFERENCES archive.customers(id))");

            // key order (z, a) is the REVERSE of alphabetical order, so sorting by
            // name and sorting by key position give different answers
            statement.execute("CREATE TABLE composite_parent (z VARCHAR(8), a VARCHAR(8), "
                    + "PRIMARY KEY (z, a))");
            statement.execute("CREATE TABLE composite_child (fz VARCHAR(8), fa VARCHAR(8), "
                    + "CONSTRAINT fk_composite FOREIGN KEY (fz, fa) "
                    + "REFERENCES composite_parent(z, a))");
            statement.execute("CREATE INDEX idx_composite ON composite_child(fz, fa)");
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
    void aForeignKeyIntoAnotherSchema_isQualifiedSoItCannotBeConfusedWithALocalOne() {
        DbTable orders = inspect().table("CROSS_SCHEMA_ORDERS").orElseThrow();

        assertThat(orders.foreignKeys())
                .extracting(foreignKey -> foreignKey.name() + " -> " + foreignKey.referencedTable())
                .containsExactly(
                        "FK_ARCHIVED -> ARCHIVE.CUSTOMERS",
                        "FK_LOCAL -> CUSTOMERS");
    }

    @Test
    void compositeKeysAndIndexes_keepKeyOrderRatherThanAlphabeticalOrder() {
        DbSchemaSnapshot snapshot = inspect();

        assertThat(snapshot.table("COMPOSITE_PARENT").orElseThrow().primaryKey().columns())
                .containsExactly("Z", "A");
        assertThat(snapshot.table("COMPOSITE_CHILD").orElseThrow().foreignKeys())
                .singleElement()
                .satisfies(foreignKey -> {
                    assertThat(foreignKey.columns()).containsExactly("FZ", "FA");
                    assertThat(foreignKey.referencedColumns()).containsExactly("Z", "A");
                });
        assertThat(snapshot.table("COMPOSITE_CHILD").orElseThrow().indexes())
                .filteredOn(index -> index.name().equals("IDX_COMPOSITE"))
                .singleElement()
                .satisfies(index -> assertThat(index.columns()).containsExactly("FZ", "FA"));
    }

    @Test
    void repeatedInspections_produceEqualSnapshots() {
        assertThat(inspect()).isEqualTo(inspect());
    }

    @Test
    void twoCapturesOfALiveSchema_serializeToIdenticalBytes(@TempDir java.nio.file.Path dir) throws Exception {
        DbSchemaSnapshotStore store = new DbSchemaSnapshotStore();
        java.nio.file.Path first = store.write(dir.resolve("first.json"), inspect());
        java.nio.file.Path second = store.write(dir.resolve("second.json"), inspect());

        // the fixture-based determinism test cannot see an inspector that returns
        // rows in driver order; this one reads the same live database twice
        assertThat(java.nio.file.Files.readAllBytes(second))
                .isEqualTo(java.nio.file.Files.readAllBytes(first));
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
