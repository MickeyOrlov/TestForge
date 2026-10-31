package io.testforge.db;

import io.testforge.core.wait.Waiter;
import io.testforge.db.datasource.DataSourceRegistry;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.awaitility.core.ConditionTimeoutException;

/**
 * Waits for rows that backend services write asynchronously (after a Kafka
 * event, an outbox relay, a background job).
 *
 * <p>Wrap a repository call with {@link #awaitRow} or {@link #awaitRows} to poll
 * until a domain object appears. Note that {@code awaitRow} and {@code awaitRows}
 * are datasource-agnostic because they poll a caller-supplied {@link Supplier}.
 *
 * <p>For JDBC-backed waiting against a specific database, use {@link #on(String)}
 * and {@link #awaitRowCount(String, String, int, Object...)}:
 *
 * <pre>{@code
 * long count = dbWaiter.on("audit").awaitRowCount(
 *         "audit_log entry for task " + taskId,
 *         "SELECT count(*) FROM audit_log WHERE task_id = ?",
 *         1,
 *         taskId);
 * }</pre>
 */
public class DbWaiter {

    /** What the poll saw when the query returned no row at all. */
    private static final Object NO_ROW = new Object();

    /** A row whose first column is SQL NULL; Waiter keeps polling on a plain null. */
    private static final Object NULL_VALUE = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };

    private final Waiter waiter;
    private final DataSourceRegistry registry;
    private final String datasourceName;

    /**
     * Legacy constructor for a datasource-agnostic {@code DbWaiter}.
     *
     * <p>Calling {@link #on(String)} or {@link #awaitRowCount(String, String, int, Object...)}
     * on an instance created with this constructor will throw {@link IllegalStateException}
     * because no {@link DataSourceRegistry} is available.
     *
     * @param waiter the core waiter instance
     */
    public DbWaiter(Waiter waiter) {
        this(waiter, null, null);
    }

    /**
     * Constructs a {@code DbWaiter} backed by a {@link DataSourceRegistry}.
     *
     * @param waiter the core waiter instance
     * @param registry the datasource registry (may be null if no datasources are configured)
     */
    public DbWaiter(Waiter waiter, DataSourceRegistry registry) {
        this(waiter, registry, null);
    }

    private DbWaiter(Waiter waiter, DataSourceRegistry registry, String datasourceName) {
        this.waiter = Objects.requireNonNull(waiter, "waiter must not be null");
        this.registry = registry;
        this.datasourceName = datasourceName;
    }

    /**
     * Returns a new {@code DbWaiter} bound to the named datasource.
     *
     * <p>This method does not mutate the receiver; it returns a new independent instance.
     * Passing {@code null}, an empty string, or a blank string binds to the default datasource.
     *
     * @param datasourceName the name of the datasource, or {@code null}/blank for default
     * @return a new {@code DbWaiter} instance bound to the specified datasource name
     * @throws IllegalStateException if no {@link DataSourceRegistry} is available on this instance
     */
    public DbWaiter on(String datasourceName) {
        if (this.registry == null) {
            throw new IllegalStateException("No DataSourceRegistry is available on this DbWaiter instance. Inject the auto-configured DbWaiter bean or construct DbWaiter with a DataSourceRegistry.");
        }
        return new DbWaiter(this.waiter, this.registry, datasourceName);
    }

    /**
     * Polls {@code query} until a non-null row is returned.
     *
     * <p>This method is datasource-agnostic; the query execution is determined by the
     * caller-supplied {@link Supplier}. Calling {@link #on(String)} does not affect this method.
     */
    public <T> T awaitRow(String description, Supplier<Optional<T>> query) {
        return waiter.await(description, () -> query.get().orElse(null), Objects::nonNull);
    }

    /**
     * Polls {@code query} until the returned list contains at least {@code minCount} rows.
     *
     * <p>This method is datasource-agnostic; the query execution is determined by the
     * caller-supplied {@link Supplier}. Calling {@link #on(String)} does not affect this method.
     */
    public <T> List<T> awaitRows(String description, Supplier<List<T>> query, int minCount) {
        return waiter.await(description, query, rows -> rows.size() >= minCount);
    }

    /**
     * Polls the resolved {@link DataSource} with {@code sql} until the first column
     * of the first row is greater than or equal to {@code minCount}.
     *
     * <pre>{@code
     * long count = dbWaiter.on("audit").awaitRowCount(
     *         "audit_log entry for task " + taskId,
     *         "SELECT count(*) FROM audit_log WHERE task_id = ?",
     *         1, taskId);
     * }</pre>
     *
     * Values go in as {@code ?} parameters, never concatenated into the SQL.
     * On timeout the failure names the last count observed.
     *
     * @param description human-readable description for waiter failure messages
     * @param sql the SQL query to execute (must return a numeric count/value in the first column)
     * @param minCount minimum count required for the wait to succeed
     * @param parameters values for the {@code ?} placeholders, in order
     * @return the last observed count (which is &gt;= minCount)
     * @throws IllegalStateException if no {@link DataSourceRegistry} is available on this instance
     * @throws IllegalArgumentException if the datasource name (or default datasource) cannot be resolved
     */
    public long awaitRowCount(String description, String sql, int minCount, Object... parameters) {
        Object last = poll(description, sql, parameters, value -> value instanceof Number number
                && number.longValue() >= minCount, "a count of at least " + minCount);
        return ((Number) last).longValue();
    }

    /**
     * Polls until the first column of the first row equals {@code expected} —
     * the asynchronous status change, without a repository:
     *
     * <pre>{@code
     * dbWaiter.awaitValue("order " + id + " paid",
     *         "SELECT status FROM orders WHERE id = ?", "PAID", id);
     * }</pre>
     *
     * Numbers compare by value ({@code 1} equals {@code 1L} and {@code 1.0}).
     * On timeout the failure names the last value observed, or that no row
     * came back.
     */
    public Object awaitValue(String description, String sql, Object expected, Object... parameters) {
        return poll(description, sql, parameters, value -> same(expected, value), String.valueOf(expected));
    }

    private Object poll(String description, String sql, Object[] parameters, Predicate<Object> until,
                        String wanted) {
        if (this.registry == null) {
            throw new IllegalStateException("No DataSourceRegistry is available on this DbWaiter instance. Inject the auto-configured DbWaiter bean or construct DbWaiter with a DataSourceRegistry.");
        }
        DataSource ds = this.registry.resolve(this.datasourceName);
        AtomicReference<Object> last = new AtomicReference<>(NO_ROW);
        AtomicReference<RuntimeException> error = new AtomicReference<>();
        try {
            waiter.await(description, () -> {
                try {
                    Object value = firstValue(ds, sql, parameters, description);
                    last.set(value);
                    error.set(null);
                    return value;
                } catch (RuntimeException e) {
                    // Waiter ignores exceptions while polling; keep the last one for the timeout message
                    error.set(e);
                    throw e;
                }
            }, until::test);
            return last.get() == NULL_VALUE ? null : last.get();
        } catch (ConditionTimeoutException e) {
            Object seen = last.get();
            String observed = error.get() != null
                    ? "the query failed: " + rootMessage(error.get())
                    : "last saw " + (seen == NO_ROW ? "no row" : seen);
            // same exception type as every other Waiter timeout; only the message says more
            throw new ConditionTimeoutException("Waited for " + description + ": expected " + wanted + ", "
                    + observed + " from " + sql, e);
        }
    }

    private static Object firstValue(DataSource ds, String sql, Object[] parameters, String description) {
        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                stmt.setObject(i + 1, parameters[i]);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                // a placeholder rather than null: Waiter treats null as "keep polling" but must not throw on it
                return rs.next() ? valueOrNullMarker(rs.getObject(1)) : NO_ROW;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to execute query for " + description + ": " + sql, e);
        }
    }

    private static Object valueOrNullMarker(Object value) {
        return value == null ? NULL_VALUE : value;
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage();
    }

    private static boolean same(Object expected, Object actual) {
        Object value = actual == NULL_VALUE ? null : actual;
        if (expected instanceof Number a && value instanceof Number b) {
            return new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString())) == 0;
        }
        return Objects.equals(expected, value);
    }
}

