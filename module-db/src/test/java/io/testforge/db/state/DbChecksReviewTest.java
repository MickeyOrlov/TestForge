package io.testforge.db.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** What a review of the deeper checks found, each pinned so it stays fixed. */
class DbChecksReviewTest {

    private DriverManagerDataSource dataSource;
    private DbState db;

    @BeforeEach
    void database() {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        db = DbState.of(dataSource);
    }

    @Test
    void tablesNamedAfterReservedWordsAreWatchedToo() throws Exception {
        execute("CREATE TABLE \"ORDER\" (id BIGINT PRIMARY KEY, status VARCHAR(8))",
                "CREATE TABLE \"USER\" (id BIGINT PRIMARY KEY, name VARCHAR(8))",
                "INSERT INTO \"ORDER\" VALUES (1, 'NEW')");
        DbSnapshot before = db.watchSchema();

        execute("UPDATE \"ORDER\" SET status = 'PAID'");

        before.diff().expect().table("ORDER", t -> t.row(1).changed("status", "NEW", "PAID")).nothingElse();
    }

    @Test
    void aTableTooBigToWatchIsNamedWithTheWayOut() throws Exception {
        execute("CREATE TABLE countries (code VARCHAR(8) PRIMARY KEY)",
                "INSERT INTO countries SELECT 'C' || x FROM SYSTEM_RANGE(1, 10001)");

        assertThatThrownBy(() -> db.watchSchema())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("more than 10000 rows")
                .hasMessageContaining("exclude it, watchSchema(\"COUNTRIES\")");
        execute("CREATE TABLE orders (id BIGINT PRIMARY KEY)");
        assertThat(db.watchSchema("countries").tables()).containsExactly("ORDERS");
    }

    @Test
    void aRuleThatCannotRunFailsAtOnceInsteadOfWaitingOutTheDeadline() throws Exception {
        execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, total NUMERIC(10,2))");

        long started = System.nanoTime();
        assertThatThrownBy(() -> db.invariants().holds("orders", "totl >= 0").assertHoldsWithin(Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Could not check holds: orders where totl >= 0");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    }

    private void execute(String... statements) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }
}
