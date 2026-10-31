package io.testforge.db.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DbSnapshotTest {

    private DriverManagerDataSource dataSource;
    private DbState db;

    @BeforeEach
    void shop() throws Exception {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        db = DbState.of(dataSource);
        execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, customer_id BIGINT, status VARCHAR(16))",
                "CREATE TABLE payments (id BIGINT PRIMARY KEY, order_id BIGINT, amount NUMERIC(10,2))",
                "CREATE TABLE loyalty_points (customer_id BIGINT PRIMARY KEY, points INT)",
                "CREATE TABLE audit_log (entry VARCHAR(64))",
                "CREATE TABLE flyway_schema_history (version VARCHAR(8) PRIMARY KEY)",
                "INSERT INTO orders VALUES (1, 7, 'NEW')",
                "INSERT INTO loyalty_points VALUES (7, 100)",
                "INSERT INTO audit_log VALUES ('order 1 created')");
    }

    private DbSnapshot watchShop() {
        return db.watch(
                db.table("orders").keyedBy("id").where("customer_id = ?", 7),
                db.table("payments").keyedBy("id"),
                db.table("loyalty_points").keyedBy("customer_id").where("customer_id = ?", 7));
    }

    private void pay() throws Exception {
        execute("UPDATE orders SET status = 'PAID' WHERE id = 1", "INSERT INTO payments VALUES (10, 1, 10.00)");
    }

    @Test
    void anActionThatChangedOnlyWhatItShouldPasses() throws Exception {
        DbSnapshot before = watchShop();

        pay();

        before.diff().expect()
                .table("orders", t -> t.changed(1, "status").row(1).changed("status", "NEW", "PAID"))
                .table("payments", t -> t.added(1).addedRow(10).has("amount", 10))
                .nothingElse();
    }

    @Test
    void aTableNobodyNamedThatMovedFailsWithItsDiff() throws Exception {
        DbSnapshot before = watchShop();

        pay();
        // the bug: paying also resets the customer's loyalty points
        execute("UPDATE loyalty_points SET points = 0 WHERE customer_id = 7");

        DbDiff diff = before.diff();
        assertThat(diff.changedTables()).containsExactly("orders", "payments", "loyalty_points");
        assertThatThrownBy(() -> diff.expect()
                .table("orders", t -> t.changed(1, "status"))
                .table("payments", t -> t.added(1))
                .nothingElse())
                .isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("tables changed that the checks did not name: [loyalty_points]")
                .hasMessageContaining("~ customer_id=7 points: 100 → 0");
        assertThatThrownBy(() -> diff.expect().onlyChanged("orders", "payments"))
                .hasMessageStartingWith("tables changed that should not have: [loyalty_points]");
    }

    @Test
    void theWholeSchemaCanBeWatchedWhenTheDatabaseIsTheTests() throws Exception {
        DbSnapshot before = db.watchSchema("flyway_schema_history");

        pay();
        execute("INSERT INTO audit_log VALUES ('order 1 paid')", "INSERT INTO flyway_schema_history VALUES ('2')");

        assertThat(before.tables()).extracting(String::toLowerCase)
                .containsExactly("audit_log", "loyalty_points", "orders", "payments");
        DbDiff diff = before.diff();
        // audit_log has no primary key: rows are matched on all columns, so a new entry is an added row
        assertThat(diff.table("AUDIT_LOG").added()).hasSize(1);
        assertThatThrownBy(() -> diff.expect()
                .table("orders", t -> t.changed(1))
                .table("payments", t -> t.added(1))
                .nothingElse())
                .hasMessageContaining("[AUDIT_LOG]")
                .hasMessageContaining("+ entry=order 1 paid");
    }

    @Test
    void misuseIsNamed() {
        assertThatThrownBy(() -> db.watch()).hasMessageContaining("at least one table");
        assertThatThrownBy(() -> db.watch(db.table("orders").keyedBy("id"), db.table("orders").keyedBy("id")))
                .hasMessageContaining("orders is watched twice");
        DbDiff diff = watchShop().diff();
        assertThatThrownBy(() -> diff.table("invoices"))
                .hasMessageContaining("'invoices' is not a watched table; watched: [orders, payments, loyalty_points]");
        assertThat(diff.toString()).startsWith("no watched table changed");
    }

    private void execute(String... statements) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }
}
