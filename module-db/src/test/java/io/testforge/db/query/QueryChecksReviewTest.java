package io.testforge.db.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** What a review of the query checks found, each pinned so it stays fixed. */
class QueryChecksReviewTest {

    private static CapturedQueries.Statement sql(String text) {
        return new CapturedQueries.Statement("shop", text, 1, 1, null, "main", 1L);
    }

    @Test
    void aCommentOrAParenthesisInFrontDoesNotHideTheKind() {
        assertThat(sql("/* load orders */ select * from orders").kind()).isEqualTo(CapturedQueries.Kind.SELECT);
        assertThat(sql("-- hint\nDELETE FROM items").kind()).isEqualTo(CapturedQueries.Kind.DELETE);
        assertThat(sql("(SELECT * FROM a) UNION (SELECT * FROM b)").kind()).isEqualTo(CapturedQueries.Kind.SELECT);
    }

    @Test
    void aWriteBehindACteIsAWrite() {
        CapturedQueries.Statement cte = sql("WITH stale AS (SELECT id FROM orders WHERE age > 30) "
                + "UPDATE orders SET status = 'X'");

        assertThat(cte.kind()).isEqualTo(CapturedQueries.Kind.UPDATE);
        assertThat(cte.unboundedWrite()).as("the WHERE belongs to the CTE, not the update").isTrue();
        assertThat(sql("WITH s AS (SELECT 1) SELECT * FROM s").kind()).isEqualTo(CapturedQueries.Kind.SELECT);
    }

    @Test
    void aSelectOverAWritingCteIsTheWrite() {
        CapturedQueries.Statement moved = sql("WITH moved AS (UPDATE accounts SET active = false RETURNING id) "
                + "SELECT count(*) FROM moved");

        assertThat(moved.kind()).isEqualTo(CapturedQueries.Kind.UPDATE);
        assertThat(moved.unboundedWrite()).isTrue();
        assertThat(moved.unfilteredRead()).isFalse();
        CapturedQueries.Statement bounded = sql("WITH gone AS MATERIALIZED (DELETE FROM items WHERE order_id = ? "
                + "RETURNING id) SELECT * FROM gone");
        assertThat(bounded.kind()).isEqualTo(CapturedQueries.Kind.DELETE);
        assertThat(bounded.unboundedWrite()).isFalse();
    }

    @Test
    void aWhereInsideASubqueryDoesNotBoundTheWrite() {
        assertThat(sql("UPDATE orders SET total = (SELECT SUM(amount) FROM items WHERE items.order_id = orders.id)")
                .unboundedWrite()).isTrue();
        assertThat(sql("DELETE FROM items WHERE order_id IN (SELECT id FROM orders WHERE status = 'X')")
                .unboundedWrite()).isFalse();
    }

    @Test
    void statementsStayReadableAfterTheActionThrew() throws Exception {
        QueryRecorder recorder = new QueryRecorder();
        DbQueries queries = new DbQueries(recorder);
        DataSource dataSource = ProxyDataSourceBuilder.create(
                        new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"))
                .listener(new QueryRecorderListener(() -> recorder)).build();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY)");
        }

        DbQueries.QueryCapture capture = queries.open();
        try (capture) {
            assertThatThrownBy(() -> {
                try (Connection connection = dataSource.getConnection();
                     Statement statement = connection.createStatement()) {
                    statement.execute("INSERT INTO orders VALUES (1)");
                    statement.execute("INSERT INTO orders VALUES (1)");
                }
            }).hasMessageContaining("PRIMARY KEY");
            assertThat(capture.queries().count()).as("readable while open").isEqualTo(2);
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
        }

        assertThat(capture.queries().count()).as("closed: later statements do not count").isEqualTo(2);
        assertThatThrownBy(() -> capture.queries().expect().noErrors())
                .hasMessageContaining("INSERT INTO orders VALUES (?)");
    }
}
