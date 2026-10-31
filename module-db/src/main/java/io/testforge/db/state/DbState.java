package io.testforge.db.state;

import io.testforge.db.datasource.DataSourceRegistry;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Entry point for table snapshots: what a test changed in the database, row by
 * row and column by column, read with plain JDBC — no entity or repository
 * needed.
 *
 * <pre>{@code
 * TableSnapshot before = dbState.table("orders").keyedBy("id")
 *         .where("customer_id = ?", customerId).take();
 * api.request().post("/orders/{id}/pay", orderId);
 * before.diff().expect().added(0).removed(0).changed(1, "status", "paid_at");
 * }</pre>
 */
public final class DbState {

    private final DataSourceRegistry registry;
    private final DataSource dataSource;

    public DbState(DataSourceRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.dataSource = null;
    }

    private DbState(DataSourceRegistry registry, DataSource dataSource) {
        this.registry = registry;
        this.dataSource = dataSource;
    }

    /** For a project that builds it by hand, without a registry. */
    public static DbState of(DataSource dataSource) {
        return new DbState(null, Objects.requireNonNull(dataSource, "dataSource"));
    }

    /** The same, against the named datasource bean. */
    public DbState on(String datasourceName) {
        if (registry == null) {
            throw new IllegalStateException("DbState.of(dataSource) has no registry to resolve '"
                    + datasourceName + "' from");
        }
        return new DbState(registry, registry.resolve(datasourceName));
    }

    public TableQuery table(String table) {
        return new TableQuery(dataSource != null ? dataSource : registry.resolveDefault(), table);
    }
}
