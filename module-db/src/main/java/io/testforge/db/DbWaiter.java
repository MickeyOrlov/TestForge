package io.testforge.db;

import io.testforge.core.wait.Waiter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Waits for rows that backend services write asynchronously (after a Kafka
 * event, an outbox relay, a background job). Wrap a repository call and the
 * waiter polls it until the row appears or the timeout from forge.wait hits.
 *
 * <pre>{@code
 * TaskRecord row = dbWaiter.awaitRow(
 *         "task_record for task " + taskId,
 *         () -> taskRepository.findByTaskId(taskId));
 * }</pre>
 */
public class DbWaiter {

    private final Waiter waiter;

    public DbWaiter(Waiter waiter) {
        this.waiter = waiter;
    }

    public <T> T awaitRow(String description, Supplier<Optional<T>> query) {
        return waiter.await(description, () -> query.get().orElse(null), Objects::nonNull);
    }

    public <T> List<T> awaitRows(String description, Supplier<List<T>> query, int minCount) {
        return waiter.await(description, query, rows -> rows.size() >= minCount);
    }
}
