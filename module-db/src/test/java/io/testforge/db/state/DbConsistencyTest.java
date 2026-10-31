package io.testforge.db.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DbConsistencyTest {

    private DriverManagerDataSource dataSource;
    private DbState db;
    private final ScheduledExecutorService relay = Executors.newSingleThreadScheduledExecutor();

    @BeforeEach
    void shop() throws Exception {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        db = DbState.of(dataSource);
        execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, total NUMERIC(10,2), item_count INT)",
                "CREATE TABLE order_items (id BIGINT PRIMARY KEY, order_id BIGINT, amount NUMERIC(10,2))",
                "CREATE TABLE outbox (id BIGINT PRIMARY KEY, aggregate_id BIGINT, type VARCHAR(32))",
                "INSERT INTO orders VALUES (1, 10.00, 2)",
                "INSERT INTO order_items VALUES (11, 1, 4.00)",
                "INSERT INTO order_items VALUES (12, 1, 6.00)",
                "INSERT INTO orders VALUES (3, 0, 0)",
                "INSERT INTO outbox VALUES (101, 1, 'OrderPlaced')",
                "INSERT INTO outbox VALUES (103, 3, 'OrderPlaced')");
    }

    @AfterEach
    void stop() {
        relay.shutdownNow();
    }

    @Test
    void totalsAndCountsThatAddUpHold() {
        db.invariants()
                .everyRowHas("orders.id", "outbox.aggregate_id")
                .totalMatches("orders.total", "orders.id", "order_items.amount", "order_items.order_id")
                .countMatches("orders.item_count", "orders.id", "order_items.order_id")
                .assertHolds();
    }

    @Test
    void aTotalOrCountThatDriftedNamesTheRowAndBothNumbers() throws Exception {
        execute("INSERT INTO orders VALUES (2, 5.00, 1)",
                "INSERT INTO order_items VALUES (21, 2, 2.00)",
                "INSERT INTO order_items VALUES (22, 2, 2.00)",
                "INSERT INTO orders VALUES (4, 7.00, 0)",
                "INSERT INTO outbox VALUES (102, 2, 'OrderPlaced')",
                "INSERT INTO outbox VALUES (104, 4, 'OrderPlaced')");

        assertThatThrownBy(() -> db.invariants()
                .totalMatches("orders.total", "orders.id", "order_items.amount", "order_items.order_id")
                .countMatches("orders.item_count", "orders.id", "order_items.order_id")
                .assertHolds())
                .hasMessageContaining("- total matches: orders.total = sum of order_items.amount: 2 rows")
                .hasMessageContaining("{id=2, total=5.00, parts_sum=4.00}")
                // an order with no items must total zero
                .hasMessageContaining("{id=4, total=7.00, parts_sum=0}")
                .hasMessageContaining("- count matches: orders.item_count = rows of order_items: 1 row")
                .hasMessageContaining("{id=2, item_count=1, child_rows=2}");
    }

    @Test
    void anOutboxRowWrittenLaterIsWaitedFor() throws Exception {
        execute("INSERT INTO orders VALUES (2, 0, 0)");
        DbInvariants outbox = db.invariants().everyRowHas("orders.id", "outbox.aggregate_id");
        assertThat(outbox.verify().holds()).isFalse();

        // the relay writes the event a moment later, as an asynchronous one does
        relay.schedule(() -> {
            try {
                execute("INSERT INTO outbox VALUES (102, 2, 'OrderPlaced')");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, 300, TimeUnit.MILLISECONDS);

        assertThat(outbox.assertHoldsWithin(Duration.ofSeconds(10)).holds()).isTrue();
    }

    @Test
    void aRuleStillBrokenAtTheDeadlineIsListed() throws Exception {
        execute("INSERT INTO orders VALUES (2, 0, 0)");

        assertThatThrownBy(() -> db.invariants().everyRowHas("orders.id", "outbox.aggregate_id")
                .assertHoldsWithin(Duration.ofMillis(300)))
                .isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("still broken after PT0.3S: 1 of 1 invariant(s) broken:")
                .hasMessageContaining("- every orders.id has a outbox.aggregate_id: 1 row")
                .hasMessageContaining("{id=2, total=0.00, item_count=0}");
    }

    @Test
    void aggregateColumnsMustShareTheirTable() {
        assertThatThrownBy(() -> db.invariants()
                .totalMatches("orders.total", "invoices.id", "order_items.amount", "order_items.order_id"))
                .hasMessageContaining("must be columns of one table");
    }

    private void execute(String... statements) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }
}
