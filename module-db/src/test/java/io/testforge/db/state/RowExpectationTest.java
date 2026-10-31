package io.testforge.db.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class RowExpectationTest {

    private DriverManagerDataSource dataSource;
    private TableSnapshot before;

    @BeforeEach
    void orders() throws Exception {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, status VARCHAR(16), version INT, total NUMERIC(10,2), "
                        + "customer_id BIGINT, created_at TIMESTAMP, updated_at TIMESTAMP)",
                "INSERT INTO orders VALUES (40, 'CANCELLED', 1, 5.00, 7, TIMESTAMP '2026-01-01 10:00:00', "
                        + "TIMESTAMP '2026-01-01 10:00:00')",
                "INSERT INTO orders VALUES (41, 'NEW', 1, 8.00, 7, TIMESTAMP '2026-01-01 10:00:00', "
                        + "TIMESTAMP '2026-01-01 10:00:00')",
                "INSERT INTO orders VALUES (42, 'NEW', 3, 10.00, 7, TIMESTAMP '2026-01-01 10:00:00', "
                        + "TIMESTAMP '2026-01-01 10:00:00')");
        before = DbState.of(dataSource).table("orders").keyedBy("id").where("customer_id = ?", 7)
                .ignoring("updated_at").take();
    }

    /** What paying order 42 should do — and only that. */
    private void pay() throws Exception {
        execute("UPDATE orders SET status = 'PAID', version = version + 1, updated_at = TIMESTAMP '2026-01-01 10:05:00' "
                        + "WHERE id = 42",
                "INSERT INTO orders VALUES (43, 'NEW', 1, 3.00, 7, TIMESTAMP '2026-01-01 10:05:00', "
                        + "TIMESTAMP '2026-01-01 10:05:00')",
                "DELETE FROM orders WHERE id = 40");
    }

    @Test
    void aRowIsCheckedColumnByColumn() throws Exception {
        pay();

        before.diff().expect()
                .added(1).removed(1).changed(1, "status", "version")
                .row(42)
                    .changed("status", "NEW", "PAID")
                    .increasedBy("version", 1)
                    .movedForward("updated_at")
                    .unchanged("created_at", "customer_id", "total")
                    .onlyThese()
                .and()
                .row(41).unchanged("status", "updated_at").onlyThese()
                .and()
                .addedRow(43).has("status", "NEW").has("total", 3).has("customer_id", 7L)
                .and()
                .removedRow(40);
    }

    @Test
    void aWrongTransitionNamesTheRowAndWhatHappened() throws Exception {
        pay();

        assertThatThrownBy(() -> before.diff().expect().row(42).changed("status", "NEW", "SHIPPED"))
                .isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("row id=42: status expected NEW → SHIPPED, was NEW → PAID in orders")
                .hasMessageContaining("~ id=42");
    }

    @Test
    void aColumnTheChecksDidNotNameFailsOnlyThese() throws Exception {
        pay();
        execute("UPDATE orders SET total = 99 WHERE id = 42");

        assertThatThrownBy(() -> before.diff().expect().row(42)
                .changed("status", "NEW", "PAID").increasedBy("version", 1).onlyThese())
                .hasMessageContaining("row id=42: columns changed that the checks did not name: [total]");
    }

    @Test
    void anIgnoredColumnCanStillBeChecked() throws Exception {
        execute("UPDATE orders SET updated_at = TIMESTAMP '2025-12-31 09:00:00' WHERE id = 41");

        TableDiff diff = before.diff();

        // ignoring(updated_at): the row does not count as changed...
        diff.expect().nothing();
        // ...but its move is kept, and it went backwards
        assertThatThrownBy(() -> diff.expect().row(41).movedForward("updated_at"))
                .hasMessageContaining("row id=41: updated_at expected to move forward, went 2026-01-01 10:00:00.0 → "
                        + "2025-12-31 09:00:00.0");
    }

    @Test
    void misusedRowsSayWhatTheyAre() throws Exception {
        pay();
        TableDiff diff = before.diff();

        assertThatThrownBy(() -> diff.expect().row(43)).hasMessageStartingWith("row id=43 was added, not changed");
        assertThatThrownBy(() -> diff.expect().row(40)).hasMessageStartingWith("row id=40 was removed");
        assertThatThrownBy(() -> diff.expect().row(41).changedTo("status", "PAID"))
                .hasMessageStartingWith("row id=41: status did not change");
        assertThatThrownBy(() -> diff.expect().addedRow(44)).hasMessageStartingWith("expected row id=44 to be added");
        assertThatThrownBy(() -> diff.expect().row(42, "x")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 key value(s) [id], got 2");
        assertThatThrownBy(() -> diff.expect().row(42).increasedBy("version", 2))
                .hasMessageContaining("version expected to increase by 2, went 3 → 4");
    }

    private void execute(String... statements) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }
}
