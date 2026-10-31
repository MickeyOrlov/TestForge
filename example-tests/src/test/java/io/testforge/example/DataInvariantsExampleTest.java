package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testforge.db.state.DbInvariants;
import io.testforge.db.state.DbState;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Rules the data must keep after an action, whatever the schema enforces:
 * a retried "place order" that inserted the order twice writes the row the
 * test expected — and breaks the business key. The JdbcTemplate plays the
 * backend; the rules are scoped to the test's own customer.
 */
@SpringBootTest
class DataInvariantsExampleTest {

    @Autowired
    DbState db;

    @Autowired
    JdbcTemplate jdbc;

    private long customer;

    @BeforeEach
    void shop() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS inv_orders (id BIGINT PRIMARY KEY, customer_id BIGINT, "
                + "external_id VARCHAR(64), status VARCHAR(16))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS inv_items (id BIGINT PRIMARY KEY, order_id BIGINT, sku VARCHAR(16))");
        customer = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE / 4);
    }

    private DbInvariants rules() {
        return db.invariants()
                .within("inv_orders", "customer_id = ?", customer)
                .within("inv_items", "id >= ?", customer)
                .noOrphans("inv_items.order_id", "inv_orders.id")
                .unique("inv_orders", "external_id")
                .allowedValues("inv_orders", "status", "NEW", "PAID");
    }

    private void placeOrder(long id) {
        jdbc.update("INSERT INTO inv_orders VALUES (?, ?, ?, 'NEW')", id, customer, "ext-" + customer);
        jdbc.update("INSERT INTO inv_items VALUES (?, ?, 'A')", id, id);
    }

    @Test
    void aCorrectOrderKeepsEveryRule() {
        placeOrder(customer);

        rules().assertHolds();
    }

    @Test
    void aRetriedOrderBreaksTheBusinessKey() {
        placeOrder(customer);
        // the bug: a timeout made the client retry, and the backend inserted the order again
        placeOrder(customer + 1);

        assertThatThrownBy(() -> rules().assertHolds())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("unique: inv_orders(external_id): 1 value")
                .hasMessageContaining("duplicates=2");
    }

    @Test
    void theOutboxEventThatArrivesLaterIsWaitedFor() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS inv_outbox (id BIGINT PRIMARY KEY, aggregate_id BIGINT)");
        placeOrder(customer);
        java.util.concurrent.ScheduledExecutorService relay = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        try {
            // the outbox relay writes the event a moment after the order, as it does in production
            relay.schedule(() -> jdbc.update("INSERT INTO inv_outbox VALUES (?, ?)", customer, customer),
                    200, java.util.concurrent.TimeUnit.MILLISECONDS);

            db.invariants()
                    .within("inv_orders", "customer_id = ?", customer)
                    .everyRowHas("inv_orders.id", "inv_outbox.aggregate_id")
                    .assertHoldsWithin(java.time.Duration.ofSeconds(10));
        } finally {
            relay.shutdownNow();
        }
    }
}
