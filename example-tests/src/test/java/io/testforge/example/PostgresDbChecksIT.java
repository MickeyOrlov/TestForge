package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testforge.db.query.CapturedQueries;
import io.testforge.db.query.DbQueries;
import io.testforge.db.state.DbSnapshot;
import io.testforge.db.state.DbState;
import io.testforge.db.state.TableSnapshot;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The table diffs, data rules and query checks of the H2 examples, against
 * PostgreSQL: lower-case identifiers, its own quoting, and the column types H2
 * does not have — {@code jsonb}, arrays, {@code uuid}, {@code timestamptz}.
 *
 * <p>Tagged {@code containers} and excluded from the default build — run with
 * {@code ./gradlew :example-tests:containersTest} when Docker is available.
 */
@SpringBootTest(properties = "forge.db.queries.enabled=true")
@Tag("containers")
@Testcontainers
class PostgresDbChecksIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    DbState db;

    @Autowired
    DbQueries queries;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    private long id;

    @BeforeEach
    void tables() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS pg_accounts (id BIGINT PRIMARY KEY, owner UUID, "
                + "balance NUMERIC(12,2), version INT, active BOOLEAN, tags TEXT[], settings JSONB, "
                + "updated_at TIMESTAMPTZ)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS \"order\" (id BIGINT PRIMARY KEY, account_id BIGINT, "
                + "total NUMERIC(12,2), status VARCHAR(16))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS pg_order_lines (id BIGINT PRIMARY KEY, order_id BIGINT, "
                + "amount NUMERIC(12,2))");
        // no primary key, and a name a LIKE pattern would also match on its neighbour
        jdbc.execute("CREATE TABLE IF NOT EXISTS pg_audit_log (account_id BIGINT, action VARCHAR(16))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS pg_auditxlog (note VARCHAR(16))");
        id = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE / 4);
        jdbc.update("INSERT INTO pg_accounts VALUES (?, ?, 10.50, 1, true, ARRAY['a','b'], "
                + "'{\"theme\": \"dark\"}', now() - interval '1 minute')", id, UUID.randomUUID());
    }

    private TableSnapshot ownAccount() {
        return db.table("pg_accounts").keyedBy("id").where("id = ?", id).take();
    }

    @Test
    void columnsOfPostgresOnlyTypesDoNotChangeByBeingReadTwice() {
        TableSnapshot before = ownAccount();

        jdbc.update("UPDATE pg_accounts SET version = version + 1 WHERE id = ?", id);

        before.diff().expect().row(id).increasedBy("version", 1).onlyThese();
    }

    @Test
    void numericTimestampAndJsonChangesAreReadAsValues() {
        TableSnapshot before = ownAccount();

        jdbc.update("UPDATE pg_accounts SET balance = balance + 5, updated_at = now(), "
                + "settings = '{\"theme\": \"light\"}', tags = ARRAY['a','b','c'] WHERE id = ?", id);

        before.diff().expect().row(id)
                .increasedBy("balance", new BigDecimal("5.00"))
                .movedForward("updated_at")
                .changedTo("settings", "{\"theme\": \"light\"}")
                .changed("tags", java.util.List.of("a", "b"), java.util.List.of("a", "b", "c"))
                .unchanged("owner", "active")
                .onlyThese();
    }

    @Test
    void watchingTheWholeSchemaCoversReservedNamesAndTablesWithoutAKey() {
        DbSnapshot before = db.watchSchema();

        jdbc.update("INSERT INTO \"order\" VALUES (?, ?, 10.00, 'NEW')", id, id);
        jdbc.update("INSERT INTO pg_audit_log VALUES (?, 'ordered')", id);

        before.diff().expect()
                .table("order", t -> t.added(1))
                .table("pg_audit_log", t -> t.added(1))
                .nothingElse();
    }

    @Test
    void aFailedCheckoutInATransactionLeavesNothing() {
        DbSnapshot before = db.watch(
                db.table("order").keyedBy("id").where("id = ?", id),
                db.table("pg_order_lines").keyedBy("id").where("order_id = ?", id));

        Throwable failure = before.expectRolledBack(() -> transaction.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO \"order\" VALUES (?, ?, 10.00, 'NEW')", id, id);
            jdbc.update("INSERT INTO pg_order_lines VALUES (?, ?, 10.00)", id, id);
            throw new IllegalStateException("card declined");
        }));

        assertThat(failure).hasMessage("card declined");
    }

    @Test
    void dataRulesRunOnPostgres() {
        jdbc.update("INSERT INTO \"order\" VALUES (?, ?, 10.00, 'NEW')", id, id);
        jdbc.update("INSERT INTO pg_order_lines VALUES (?, ?, 4.00)", id, id);
        jdbc.update("INSERT INTO pg_order_lines VALUES (?, ?, 6.00)", id + 1, id);

        db.invariants()
                .within("order", "account_id = ?", id)
                .within("pg_order_lines", "order_id = ?", id)
                .noOrphans("pg_order_lines.order_id", "order.id")
                .unique("order", "account_id")
                .allowedValues("order", "status", "NEW", "PAID")
                .notNull("order", "total")
                .totalMatches("order.total", "order.id", "pg_order_lines.amount", "pg_order_lines.order_id")
                .assertHolds();
    }

    @Test
    void aBrokenTotalIsReportedOnPostgres() {
        jdbc.update("INSERT INTO \"order\" VALUES (?, ?, 10.00, 'NEW')", id, id);
        jdbc.update("INSERT INTO pg_order_lines VALUES (?, ?, 4.00)", id, id);

        assertThatThrownBy(() -> db.invariants()
                .within("order", "account_id = ?", id)
                .within("pg_order_lines", "order_id = ?", id)
                .totalMatches("order.total", "order.id", "pg_order_lines.amount", "pg_order_lines.order_id")
                .assertHolds())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("order.total");
    }

    @Test
    void statementsAreClassifiedTheSameWay() {
        CapturedQueries sql = queries.during(() -> {
            jdbc.queryForList("WITH moved AS (UPDATE pg_accounts SET active = false RETURNING id) "
                    + "SELECT count(*) FROM moved");
            jdbc.update("/* close */ UPDATE pg_accounts SET active = true WHERE id = ?", id);
            jdbc.queryForList("SELECT * FROM \"order\"");
        });

        assertThat(sql.statements()).extracting(CapturedQueries.Statement::kind).containsExactly(
                CapturedQueries.Kind.UPDATE, CapturedQueries.Kind.UPDATE, CapturedQueries.Kind.SELECT);
        assertThat(sql.statements().get(2).tables()).containsExactly("order");
        assertThatThrownBy(() -> sql.expect().noUnboundedWrites())
                .hasMessageContaining("UPDATE pg_accounts SET active = false");
        assertThatThrownBy(() -> sql.expect().noUnfilteredReads())
                .hasMessageContaining("\"order\"");
    }

    @Test
    void aSwallowedDuplicateKeyIsStillSeen() {
        CapturedQueries sql = queries.during(() -> {
            try {
                jdbc.update("INSERT INTO pg_accounts (id) VALUES (?)", id);
            } catch (DuplicateKeyException ignored) {
                // swallowed by the code under test
            }
        });

        assertThatThrownBy(() -> sql.expect().noErrors())
                .hasMessageContaining("statements failed inside the action")
                .hasMessageContaining("INSERT INTO pg_accounts (id) VALUES (?)");
    }
}
