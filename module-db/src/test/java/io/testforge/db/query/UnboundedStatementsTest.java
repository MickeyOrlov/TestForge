package io.testforge.db.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class UnboundedStatementsTest {

    private static CapturedQueries.Statement sql(String text) {
        return new CapturedQueries.Statement("shop", text, 1, 1, null, "main", Thread.currentThread().threadId());
    }

    private static CapturedQueries captured(String... statements) {
        return new CapturedQueries(Arrays.stream(statements).map(UnboundedStatementsTest::sql).toList());
    }

    @Test
    void writesThatReachEveryRowAreRecognised() {
        assertThat(sql("UPDATE orders SET status = 'PAID'").unboundedWrite()).isTrue();
        // a 'where' inside a literal is not a clause
        assertThat(sql("UPDATE orders SET note = 'paid where asked'").unboundedWrite()).isTrue();
        assertThat(sql("DELETE FROM items").unboundedWrite()).isTrue();
        assertThat(sql("truncate table items").unboundedWrite()).isTrue();
        assertThat(sql("UPDATE orders SET status = ? WHERE id = ?").unboundedWrite()).isFalse();
        assertThat(sql("DELETE FROM items WHERE order_id = ?").unboundedWrite()).isFalse();
        assertThat(sql("INSERT INTO items VALUES (?, ?)").unboundedWrite()).isFalse();
    }

    @Test
    void readsOfAWholeTableAreRecognised() {
        assertThat(sql("SELECT * FROM orders").unfilteredRead()).isTrue();
        assertThat(sql("select tr1_0.id, tr1_0.status from task_record tr1_0").unfilteredRead()).isTrue();
        assertThat(sql("SELECT * FROM orders o JOIN items i ON i.order_id = o.id").tables())
                .containsExactly("orders", "items");
        assertThat(sql("SELECT * FROM orders WHERE id = ?").unfilteredRead()).isFalse();
        assertThat(sql("SELECT * FROM orders LIMIT 10").unfilteredRead()).isFalse();
        assertThat(sql("SELECT * FROM orders ORDER BY id FETCH FIRST 10 ROWS ONLY").unfilteredRead()).isFalse();
        assertThat(sql("SELECT 1").unfilteredRead()).as("no table read").isFalse();
        assertThat(sql("SELECT * FROM \"Orders\"").tables()).containsExactly("orders");
    }

    @Test
    void theExpectationsListTheOffendingStatements() {
        CapturedQueries sql = captured(
                "SELECT * FROM orders WHERE id = 1",
                "UPDATE orders SET status = 'PAID'",
                "UPDATE orders SET status = 'NEW'",
                "SELECT * FROM countries",
                "SELECT * FROM public.currencies",
                "SELECT * FROM orders");

        assertThatThrownBy(() -> sql.expect().noUnboundedWrites())
                .isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("writes without a WHERE (every row of the table): "
                        + "[2 × UPDATE orders SET status = ?]");
        assertThatThrownBy(() -> sql.expect().noUnfilteredReads("countries", "currencies"))
                .hasMessageStartingWith("reads of a whole table (no WHERE or LIMIT): [1 × SELECT * FROM orders]");
        captured("SELECT * FROM countries", "SELECT * FROM public.currencies")
                .expect().noUnfilteredReads("countries", "currencies").noUnboundedWrites();
        assertThat(List.of(sql("SELECT * FROM orders WHERE id IN (SELECT order_id FROM items)").unfilteredRead()))
                .as("a subquery under a filtered outer query is not flagged — a known limit").containsExactly(false);
    }
}
