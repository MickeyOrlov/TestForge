package io.testforge.db;

import io.testforge.core.wait.Waiter;
import io.testforge.db.datasource.DataSourceRegistry;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * Waits for rows that backend services write asynchronously (after a Kafka
 * event, an outbox relay, a background job).
 *
 * <p>Wrap a repository call with {@link #awaitRow} or {@link #awaitRows} to poll
 * until a domain object appears. Note that {@code awaitRow} and {@code awaitRows}
 * are datasource-agnostic because they poll a caller-supplied {@link Supplier}.
 *
 * <p>For JDBC-backed waiting against a specific database, use {@link #on(String)}
 * and {@link #awaitRowCount(String, String, int)}:
 *
 * <pre>{@code
 * long count = dbWaiter.on("audit").awaitRowCount(
 *         "audit_log entry for task " + taskId,
 *         "SELECT count(*) FROM audit_log WHERE task_id = '" + taskId + "'",
 *         1);
 * }</pre>
 */
public class DbWaiter {

    private final Waiter waiter;
    private final DataSourceRegistry registry;
    private final String datasourceName;

    /**
     * Legacy constructor for a datasource-agnostic {@code DbWaiter}.
     *
     * <p>Calling {@link #on(String)} or {@link #awaitRowCount(String, String, int)}
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
     *         "SELECT count(*) FROM audit_log WHERE task_id = '" + taskId + "'",
     *         1);
     * }</pre>
     *
     * @param description human-readable description for waiter failure messages
     * @param sql the SQL query to execute (must return a numeric count/value in the first column)
     * @param minCount minimum count required for the wait to succeed
     * @return the last observed count (which is &gt;= minCount)
     * @throws IllegalStateException if no {@link DataSourceRegistry} is available on this instance
     * @throws IllegalArgumentException if the datasource name (or default datasource) cannot be resolved
     */
    public long awaitRowCount(String description, String sql, int minCount) {
        if (this.registry == null) {
            throw new IllegalStateException("No DataSourceRegistry is available on this DbWaiter instance. Inject the auto-configured DbWaiter bean or construct DbWaiter with a DataSourceRegistry.");
        }
        DataSource ds = this.registry.resolve(this.datasourceName);
        return waiter.await(description, () -> {
            try (Connection conn = ds.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                return 0L;
            } catch (SQLException e) {
                throw new RuntimeException("Failed to execute row count query for " + description + ": " + sql, e);
            }
        }, count -> count >= minCount);
    }
}

