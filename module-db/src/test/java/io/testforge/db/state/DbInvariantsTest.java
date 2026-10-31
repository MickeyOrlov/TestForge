package io.testforge.db.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DbInvariantsTest {

    enum Status { NEW, PAID, CANCELLED }

    private DriverManagerDataSource dataSource;
    private DbState db;

    @BeforeEach
    void shop() throws Exception {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        db = DbState.of(dataSource);
        // no foreign key, no unique constraint, no check: the schema enforces nothing
        execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, customer_id BIGINT, external_id VARCHAR(32), "
                        + "status VARCHAR(16), total NUMERIC(10,2))",
                "CREATE TABLE order_items (id BIGINT PRIMARY KEY, order_id BIGINT, sku VARCHAR(16))",
                "INSERT INTO orders VALUES (1, 7, 'ext-1', 'NEW', 10.00)",
                "INSERT INTO orders VALUES (2, 7, 'ext-2', 'PAID', 5.00)",
                "INSERT INTO order_items VALUES (100, 1, 'A')",
                "INSERT INTO order_items VALUES (101, 2, 'B')",
                // another customer's data on the same database
                "INSERT INTO orders VALUES (9, 8, 'ext-9', 'LEGACY', -1.00)",
                "INSERT INTO order_items VALUES (900, 999, 'Z')");
    }

    private DbInvariants rulesForCustomer7() {
        return db.invariants()
                .within("orders", "customer_id = ?", 7)
                .within("order_items", "order_id IN (SELECT id FROM orders WHERE customer_id = ?)", 7)
                .noOrphans("order_items.order_id", "orders.id")
                .unique("orders", "external_id")
                .allowedValues("orders", "status", Status.NEW, Status.PAID, Status.CANCELLED)
                .notNull("orders", "customer_id", "external_id")
                .holds("orders", "total >= 0");
    }

    @Test
    void cleanDataHoldsEveryRule() {
        DbInvariants.Report report = rulesForCustomer7().assertHolds();

        assertThat(report.rules()).isEqualTo(6);
        assertThat(report).hasToString("all 6 invariant(s) hold");
    }

    @Test
    void everyBrokenRuleIsListedWithItsRows() throws Exception {
        // a retry inserted the order twice, a delete left an item behind, a bad status, a NULL, a negative total
        execute("INSERT INTO orders VALUES (3, 7, 'ext-1', 'PAYED', -2.00)",
                "INSERT INTO orders VALUES (4, 7, NULL, 'NEW', 1.00)",
                "DELETE FROM orders WHERE id = 2");

        DbInvariants rules = db.invariants()
                .within("orders", "customer_id = ?", 7)
                .noOrphans("order_items.order_id", "orders.id")
                .unique("orders", "external_id")
                .allowedValues("orders", "status", Status.NEW, Status.PAID, Status.CANCELLED)
                .notNull("orders", "external_id")
                .holds("orders", "total >= 0");

        assertThatThrownBy(rules::assertHolds)
                .isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("5 of 5 invariant(s) broken:")
                .hasMessageContaining("- no orphans: every order_items.order_id exists as orders.id: 2 rows")
                .hasMessageContaining("{id=101, order_id=2, sku=B}")
                .hasMessageContaining("- unique: orders(external_id): 1 value\n      {external_id=ext-1, duplicates=2}")
                .hasMessageContaining("- allowed values: orders.status in [NEW, PAID, CANCELLED]: 1 value\n"
                        + "      {status=PAYED, rows_with_it=1}")
                .hasMessageContaining("- not null: orders.external_id: 1 row")
                .hasMessageContaining("- holds: orders where total >= 0: 1 row");
    }

    @Test
    void theScopeKeepsOtherTestsDataOutOfTheVerdict() {
        // customer 8's orphan, unknown status and negative total exist, but are not this test's
        DbInvariants.Report everything = db.invariants()
                .noOrphans("order_items.order_id", "orders.id")
                .allowedValues("orders", "status", "NEW", "PAID", "CANCELLED")
                .holds("orders", "total >= 0")
                .verify();

        assertThat(everything.violations()).hasSize(3);
        assertThat(rulesForCustomer7().verify().holds()).isTrue();
    }

    @Test
    void longListsAreSampled() throws Exception {
        for (int i = 0; i < 12; i++) {
            execute("INSERT INTO order_items VALUES (" + (200 + i) + ", " + (500 + i) + ", 'X')");
        }

        DbInvariants.Report report = db.invariants().noOrphans("order_items.order_id", "orders.id").verify();

        assertThat(report.violations()).singleElement().satisfies(violation -> {
            assertThat(violation.rows()).isEqualTo(13);
            assertThat(violation.samples()).hasSize(DbInvariants.SAMPLE);
        });
        assertThat(report.toString()).contains("... and 8 more");
    }

    @Test
    void misuseIsNamed() {
        assertThatThrownBy(() -> db.invariants().verify()).hasMessageContaining("No invariant to verify");
        assertThatThrownBy(() -> db.invariants().noOrphans("order_id", "orders.id"))
                .hasMessageContaining("'order_id' must be table.column");
        assertThatThrownBy(() -> db.invariants().unique("orders", "id; DROP TABLE orders"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> db.invariants().allowedValues("orders", "status"))
                .hasMessageContaining("needs at least one value");
        assertThatThrownBy(() -> db.invariants().holds("orders", "no_such_column > 0").verify())
                .hasMessageStartingWith("Could not check holds: orders where no_such_column > 0");
    }

    private void execute(String... statements) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }
}
