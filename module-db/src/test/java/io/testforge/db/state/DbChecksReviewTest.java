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
    void reservedWordsTheTestNamesAreQuotedInEveryCheck() throws Exception {
        execute("CREATE TABLE \"ORDER\" (id BIGINT PRIMARY KEY, \"USER\" VARCHAR(8), total NUMERIC(10,2))",
                "CREATE TABLE lines (id BIGINT PRIMARY KEY, order_id BIGINT, amount NUMERIC(10,2))");
        TableSnapshot before = db.table("order").keyedBy("id").take();

        db.into("order").keyedBy("id").withoutCleanup().insert(java.util.Map.of("id", 1, "user", "anna", "total", 5));
        db.into("lines").keyedBy("id").withoutCleanup().insert(java.util.Map.of("id", 1, "order_id", 1, "amount", 5));

        before.diff().expect().added(1);
        db.invariants()
                .within("order", "\"USER\" = ?", "anna")
                .noOrphans("lines.order_id", "order.id")
                .unique("order", "user")
                .allowedValues("order", "user", "anna")
                .notNull("order", "user")
                .totalMatches("order.total", "order.id", "lines.amount", "lines.order_id")
                .holds("order", "total >= 0")
                .assertHolds();
    }

    @Test
    void anArrayReadTwiceIsTheSameValue() throws Exception {
        execute("CREATE TABLE tagged (id BIGINT PRIMARY KEY, tags VARCHAR ARRAY, version INT)",
                "INSERT INTO tagged VALUES (1, ARRAY['a', 'b'], 1)");
        TableSnapshot before = db.table("tagged").keyedBy("id").take();

        execute("UPDATE tagged SET version = 2");

        before.diff().expect().row(1).changed("version", 1, 2).onlyThese();
        execute("UPDATE tagged SET tags = ARRAY['a']");
        before.diff().expect().row(1).changed("tags", java.util.List.of("a", "b"), java.util.List.of("a"));
    }

    @Test
    void aTableWithoutAKeyIsKeyedByItsOwnColumnsOnly() throws Exception {
        // as a LIKE pattern, AUDIT_LOG also matches AUDITXLOG
        execute("CREATE TABLE audit_log (account_id BIGINT, action VARCHAR(8))",
                "CREATE TABLE auditxlog (note VARCHAR(8))");
        DbSnapshot before = db.watchSchema();

        execute("INSERT INTO audit_log VALUES (1, 'paid')");

        before.diff().expect().table("audit_log", t -> t.added(1)).nothingElse();
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
