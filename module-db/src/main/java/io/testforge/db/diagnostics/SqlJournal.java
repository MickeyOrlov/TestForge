package io.testforge.db.diagnostics;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * The last SQL statements the current thread ran, bounded, for a failed test
 * to show. Per thread like {@code ScenarioContext}: parallel tests never see
 * each other's statements.
 */
public class SqlJournal {

    /**
     * One executed statement.
     *
     * @param parameters bound values in order, each rendered and shortened; one list per batch entry
     * @param error      the failure, or {@code null} when it succeeded
     */
    public record Statement(String dataSource, String sql, List<List<String>> parameters, long millis,
                            String error) {

        public Statement {
            parameters = parameters.stream().map(List::copyOf).toList();
        }
    }

    private final int capacity;
    private final ThreadLocal<Deque<Statement>> statements = ThreadLocal.withInitial(ArrayDeque::new);

    public SqlJournal(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public void record(Statement statement) {
        Deque<Statement> recorded = statements.get();
        recorded.addLast(statement);
        while (recorded.size() > capacity) {
            recorded.removeFirst();
        }
    }

    /** Oldest first. */
    public List<Statement> recent() {
        return List.copyOf(statements.get());
    }

    public void clear() {
        statements.get().clear();
    }
}
