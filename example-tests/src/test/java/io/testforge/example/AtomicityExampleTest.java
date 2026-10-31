package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testforge.db.state.DbSnapshot;
import io.testforge.db.state.DbState;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A checkout that fails must leave nothing behind. The same writes inside one
 * transaction roll back; outside one, the order and payment stay, and the
 * check says so with the rows.
 */
@SpringBootTest
class AtomicityExampleTest {

    static final class CardDeclined extends RuntimeException {
        CardDeclined() {
            super("card declined");
        }
    }

    @Autowired
    DbState db;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    private long order;

    @BeforeEach
    void shop() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS atomic_orders (id BIGINT PRIMARY KEY, status VARCHAR(16))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS atomic_payments (id BIGINT PRIMARY KEY, order_id BIGINT)");
        order = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE / 2);
    }

    private DbSnapshot watchOrder() {
        return db.watch(
                db.table("atomic_orders").keyedBy("id").where("id = ?", order),
                db.table("atomic_payments").keyedBy("id").where("order_id = ?", order));
    }

    private void checkout() {
        jdbc.update("INSERT INTO atomic_orders VALUES (?, 'NEW')", order);
        jdbc.update("INSERT INTO atomic_payments VALUES (?, ?)", order, order);
        throw new CardDeclined();
    }

    @Test
    void aDeclinedCheckoutInOneTransactionLeavesNothing() {
        Throwable failure = watchOrder().expectRolledBack(() -> transaction.executeWithoutResult(status -> checkout()));

        assertThat(failure).isInstanceOf(CardDeclined.class);
    }

    @Test
    void aDeclinedCheckoutWithoutATransactionIsCaught() {
        // the bug: the service method lost its @Transactional
        assertThatThrownBy(() -> watchOrder().expectRolledBack(this::checkout))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("the action failed (CardDeclined: card declined) but left changes behind")
                .hasMessageContaining("atomic_orders")
                .hasMessageContaining("atomic_payments");
    }
}
