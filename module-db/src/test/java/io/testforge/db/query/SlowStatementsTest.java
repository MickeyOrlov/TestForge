package io.testforge.db.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SlowStatementsTest {

    private static CapturedQueries.Statement sql(String text, long millis, String error) {
        return new CapturedQueries.Statement("shop", text, 1, millis, error, "http-nio-1", Thread.currentThread());
    }

    private final CapturedQueries scenario = new CapturedQueries(List.of(
            sql("SELECT * FROM orders WHERE customer_id = ?", 4, null),
            sql("SELECT * FROM report_lines WHERE period = ?", 1840, null),
            sql("INSERT INTO orders VALUES (?, ?)", 2, "SQLIntegrityConstraintViolationException"),
            sql("INSERT INTO orders VALUES (?, ?)", 3, null),
            sql("UPDATE stats SET total = total + 1 WHERE id = ?", 650, null)));

    @Test
    void theSlowestStatementsAndTheTotalAreAtHand() {
        assertThat(scenario.totalMillis()).isEqualTo(2499);
        assertThat(scenario.slowest(2)).extracting(CapturedQueries.Statement::millis).containsExactly(1840L, 650L);
        assertThat(scenario.toString()).startsWith("5 statements (SELECT 2, INSERT 2, UPDATE 1), 2499 ms in the database");
    }

    @Test
    void aStatementOverTheLimitIsNamedWithItsThread() {
        assertThatThrownBy(() -> scenario.expect().noneSlowerThan(Duration.ofMillis(500)))
                .isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("statements slower than 500 ms:\n"
                        + "  1840 ms  SELECT * FROM report_lines WHERE period = ?  [http-nio-1]\n"
                        + "  650 ms  UPDATE stats SET total = total + ? WHERE id = ?  [http-nio-1]");
        scenario.expect().noneSlowerThan(Duration.ofSeconds(2));
    }

    @Test
    void theDatabaseTimeOfAnActionHasABudget() {
        assertThatThrownBy(() -> scenario.expect().totalUnder(Duration.ofSeconds(1)))
                .hasMessageStartingWith("expected under 1000 ms in the database, spent 2499 ms; slowest:\n"
                        + "  1840 ms  SELECT * FROM report_lines WHERE period = ?");
        scenario.expect().totalUnder(Duration.ofSeconds(3));
    }

    @Test
    void aStatementThatFailedInsideASuccessfulActionIsCaught() {
        assertThat(scenario.failed()).hasSize(1);
        assertThatThrownBy(() -> scenario.expect().noErrors())
                .hasMessageStartingWith("statements failed inside the action:\n"
                        + "  SQLIntegrityConstraintViolationException  INSERT INTO orders VALUES (?, ?)");
    }
}
