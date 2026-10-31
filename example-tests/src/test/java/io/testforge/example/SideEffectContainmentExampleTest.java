package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testforge.db.state.DbSnapshot;
import io.testforge.db.state.DbState;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * "Paying changes the order and adds a payment — and nothing else": every
 * table the action may touch is watched, and a table that moved without being
 * named fails the test with its diff. The JdbcTemplate plays the backend.
 */
@SpringBootTest
class SideEffectContainmentExampleTest {

    @Autowired
    DbState db;

    @Autowired
    JdbcTemplate jdbc;

    private long customer;

    @BeforeEach
    void shop() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS shop_orders (id BIGINT PRIMARY KEY, customer_id BIGINT, status VARCHAR(16))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS shop_payments (id BIGINT PRIMARY KEY, customer_id BIGINT, amount NUMERIC(10,2))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS shop_points (customer_id BIGINT PRIMARY KEY, points INT)");
        // the test's own customer on a shared database: unique per run, selected by where(...)
        customer = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE / 2);
        jdbc.update("INSERT INTO shop_orders VALUES (?, ?, 'NEW')", customer, customer);
        jdbc.update("INSERT INTO shop_points VALUES (?, 100)", customer);
    }

    private DbSnapshot watchCustomer() {
        return db.watch(
                db.table("shop_orders").keyedBy("id").where("customer_id = ?", customer),
                db.table("shop_payments").keyedBy("id").where("customer_id = ?", customer),
                db.table("shop_points").keyedBy("customer_id").where("customer_id = ?", customer));
    }

    private void pay() {
        jdbc.update("UPDATE shop_orders SET status = 'PAID' WHERE id = ?", customer);
        jdbc.update("INSERT INTO shop_payments VALUES (?, ?, 10.00)", customer, customer);
    }

    @Test
    void payingTouchesTheOrderAndThePaymentOnly() {
        DbSnapshot before = watchCustomer();

        pay();

        before.diff().expect()
                .table("shop_orders", t -> t.row(customer).changed("status", "NEW", "PAID").onlyThese())
                .table("shop_payments", t -> t.added(1))
                .nothingElse();
    }

    @Test
    void aSideEffectInATableNobodyNamedIsCaught() {
        DbSnapshot before = watchCustomer();

        pay();
        // the bug: paying also wipes the customer's loyalty points
        jdbc.update("UPDATE shop_points SET points = 0 WHERE customer_id = ?", customer);

        assertThatThrownBy(() -> before.diff().expect()
                .table("shop_orders", t -> t.changed(1, "status"))
                .table("shop_payments", t -> t.added(1))
                .nothingElse())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("tables changed that the checks did not name: [shop_points]")
                .hasMessageContaining("points: 100 → 0");
    }
}
