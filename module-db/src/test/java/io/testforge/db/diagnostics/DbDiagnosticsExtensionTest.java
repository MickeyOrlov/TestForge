package io.testforge.db.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;
import static org.junit.platform.testkit.engine.EventConditions.event;
import static org.junit.platform.testkit.engine.EventConditions.finishedWithFailure;
import static org.junit.platform.testkit.engine.EventConditions.test;
import static org.junit.platform.testkit.engine.TestExecutionResultConditions.message;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;

class DbDiagnosticsExtensionTest {

    @BeforeEach
    void reset() {
        DbDiagnosticsTestkitFixture.WRITTEN.clear();
    }

    private static void run(String method) {
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectMethod(DbDiagnosticsTestkitFixture.class, method))
                .execute()
                .testEvents()
                .assertThatEvents()
                .haveExactly("failsAfterItsQueries".equals(method) ? 1 : 0,
                        event(test(), finishedWithFailure(message(m -> m.contains("the order status")))));
    }

    @Test
    void aFailedTestPublishesItsLastStatementsWithParametersAndKeepsItsOwnFailure() {
        run("failsAfterItsQueries");

        assertThat(DbDiagnosticsTestkitFixture.WRITTEN).singleElement().satisfies(written -> {
            assertThat(written).startsWith("module-db/DbDiagnosticsTestkitFixture-failsAfterItsQueries.md\n");
            assertThat(written)
                    .contains("Failure: [the order status]")
                    .contains("INSERT INTO orders VALUES (?, ?)")
                    .contains("Parameters: [1, NEW]")
                    .contains("Batch of 2:\n\n- [2, NEW]\n- [3, PAID]")
                    .contains("SELECT status FROM orders WHERE id = ?")
                    .as("only the last three are kept").doesNotContain("DELETE FROM orders");
        });
    }

    @Test
    void aPassingTestPublishesNothing() {
        run("passes");

        assertThat(DbDiagnosticsTestkitFixture.WRITTEN).isEmpty();
    }

    @Test
    void theJournalIsBoundedAndPerThread() throws Exception {
        SqlJournal journal = new SqlJournal(2);
        for (int i = 1; i <= 3; i++) {
            journal.record(new SqlJournal.Statement("ds", "SELECT " + i, List.of(), 1, null));
        }
        List<String> otherThread = new java.util.ArrayList<>();
        Thread thread = new Thread(() -> journal.recent().forEach(s -> otherThread.add(s.sql())));
        thread.start();
        thread.join();

        assertThat(journal.recent()).extracting(SqlJournal.Statement::sql).containsExactly("SELECT 2", "SELECT 3");
        assertThat(otherThread).isEmpty();
    }

    @Test
    void aFailedStatementSaysSo() {
        String markdown = DbDiagnosticsExtension.render("t", List.of(new SqlJournal.Statement("dataSource",
                "SELECT * FROM missing", List.of(), 2, "org.h2.jdbc.JdbcSQLSyntaxErrorException: Table not found")),
                new AssertionError("boom"));

        assertThat(markdown).contains("## 1. dataSource (2 ms) — failed").contains("Error: org.h2");
    }
}
