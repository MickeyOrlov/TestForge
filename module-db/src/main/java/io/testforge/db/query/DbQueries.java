package io.testforge.db.query;

import java.util.function.Supplier;

/**
 * What SQL an action ran: how many statements, of which kind, how often the
 * same one — the N+1 that loads 50 orders with 51 queries, the delete nobody
 * expected. Needs {@code forge.db.queries.enabled=true}, which wraps the
 * datasources like {@code log-sql} does.
 *
 * <pre>{@code
 * CapturedQueries sql = queries.during(() -> api.listOrders(customer));
 * sql.expect().atMost(3).noRepeated(2).none(CapturedQueries.Kind.DELETE);
 * }</pre>
 *
 * <p>A window sees statements from every thread, so the application's own
 * request threads count when it runs in the test's JVM. On a suite running
 * tests in parallel, use {@link #duringThisThread} or keep such tests serial.
 * Statements TestForge itself runs inside the window — a {@code DbState}
 * snapshot — count too: take snapshots outside it.
 */
public final class DbQueries {

    private final QueryRecorder recorder;

    public DbQueries(QueryRecorder recorder) {
        this.recorder = recorder;
    }

    /** Every statement any thread ran while {@code action} ran. */
    public CapturedQueries during(Runnable action) {
        return capture(false, () -> {
            action.run();
            return null;
        }).queries();
    }

    /**
     * As {@link #during(Runnable)}, keeping what the action returned. A name of
     * its own: an overload of {@code during} would capture
     * {@code during(() -> repository.findAll())} as this one.
     */
    public <T> Result<T> returning(Supplier<T> action) {
        return capture(false, action);
    }

    /** Only the statements the calling thread ran — an in-thread call, a MockMvc request. */
    public CapturedQueries duringThisThread(Runnable action) {
        return capture(true, () -> {
            action.run();
            return null;
        }).queries();
    }

    private <T> Result<T> capture(boolean thisThreadOnly, Supplier<T> action) {
        CapturedQueries.Window window = recorder.open(thisThreadOnly);
        try {
            T value = action.get();
            return new Result<>(value, window.close());
        } finally {
            recorder.close(window);
        }
    }

    /** What the action returned, and what it ran. */
    public record Result<T>(T value, CapturedQueries queries) {
    }
}
