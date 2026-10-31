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

class RollbackTest {

    static final class CardDeclined extends RuntimeException {
        CardDeclined() {
            super("card declined");
        }
    }

    private DriverManagerDataSource dataSource;
    private DbState db;
    private final ScheduledExecutorService compensator = Executors.newSingleThreadScheduledExecutor();

    @BeforeEach
    void shop() throws Exception {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        db = DbState.of(dataSource);
        execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, status VARCHAR(16))",
                "CREATE TABLE payments (id BIGINT PRIMARY KEY, order_id BIGINT)");
    }

    @AfterEach
    void stop() {
        compensator.shutdownNow();
    }

    private DbSnapshot watch() {
        return db.watch(db.table("orders").keyedBy("id"), db.table("payments").keyedBy("id"));
    }

    /** Writes the order and the payment, then the card is declined. */
    private void checkout(boolean inOneTransaction) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(!inOneTransaction);
            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO orders VALUES (1, 'NEW')");
                statement.execute("INSERT INTO payments VALUES (10, 1)");
                throw new CardDeclined();
            } catch (CardDeclined e) {
                if (inOneTransaction) {
                    connection.rollback();
                }
                throw e;
            }
        }
    }

    @Test
    void anAtomicActionThatFailedLeftNothing() {
        Throwable failure = watch().expectRolledBack(() -> checkout(true));

        assertThat(failure).isInstanceOf(CardDeclined.class);
    }

    @Test
    void anActionThatFailedHalfwayIsCaughtWithWhatItLeft() {
        assertThatThrownBy(() -> watch().expectRolledBack(() -> checkout(false)))
                .isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("the action failed (CardDeclined: card declined) but left changes behind"
                        + " — it is not atomic:")
                .hasMessageContaining("+ id=1 {status=NEW}")
                .hasMessageContaining("+ id=10 {order_id=1}")
                .hasCauseInstanceOf(CardDeclined.class);
    }

    @Test
    void anActionThatWasExpectedToFailButSucceededSaysWhatItChanged() {
        assertThatThrownBy(() -> watch().expectRolledBack(() -> execute("INSERT INTO orders VALUES (2, 'NEW')")))
                .hasMessageStartingWith("expected the action to fail, but it succeeded; it changed:")
                .hasMessageContaining("+ id=2 {status=NEW}");
    }

    @Test
    void aRejectionWithoutAnExceptionLeavesTheTablesAsTheyWere() throws Exception {
        DbSnapshot before = watch();
        TableSnapshot orders = db.table("orders").keyedBy("id").take();

        before.assertUnchanged();
        orders.assertUnchanged();
        execute("INSERT INTO orders VALUES (3, 'NEW')");
        assertThatThrownBy(before::assertUnchanged).hasMessageContaining("+ id=3 {status=NEW}");
        assertThatThrownBy(orders::assertUnchanged).hasMessageContaining("expected no changes");
    }

    @Test
    void aCompensationThatUndoesTheWriteLaterIsWaitedFor() throws Exception {
        DbSnapshot before = watch();
        execute("INSERT INTO orders VALUES (4, 'NEW')");
        // the saga's compensating step runs a moment later
        compensator.schedule(() -> {
            try {
                execute("DELETE FROM orders WHERE id = 4");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, 200, TimeUnit.MILLISECONDS);

        before.assertUnchangedWithin(Duration.ofSeconds(10));
    }

    @Test
    void aCompensationThatNeverCameIsListed() throws Exception {
        TableSnapshot before = db.table("orders").keyedBy("id").take();
        execute("INSERT INTO orders VALUES (5, 'NEW')");

        assertThatThrownBy(() -> before.assertUnchangedWithin(Duration.ofMillis(300)))
                .hasMessageStartingWith("still changed after PT0.3S — the compensation did not undo:")
                .hasMessageContaining("+ id=5 {status=NEW}");
    }

    private void execute(String... statements) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }
}
