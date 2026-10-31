package io.testforge.db.state;

import io.testforge.db.datasource.DataSourceRegistry;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        return new TableQuery(target(), table);
    }

    /**
     * Several tables at once, so a test can assert that an action changed what
     * it should and nothing else; see {@link DbSnapshot}.
     */
    public DbSnapshot watch(TableQuery... queries) {
        if (queries == null || queries.length == 0) {
            throw new IllegalArgumentException("watch(...) needs at least one table");
        }
        Map<String, TableSnapshot> tables = new LinkedHashMap<>();
        for (TableQuery query : queries) {
            if (tables.putIfAbsent(query.table(), query.take()) != null) {
                throw new IllegalArgumentException("table " + query.table() + " is watched twice");
            }
        }
        return new DbSnapshot(tables);
    }

    /**
     * Every table of the connection's current schema, each keyed by its
     * primary key, except {@code excluded} (case ignored) — for a database the
     * test owns (an embedded one, a container per run), where "nothing else in
     * the database moved" is a checkable statement. On a shared database every
     * parallel test's writes would count; use {@link #watch} with {@code where}
     * clauses there. A table without a primary key is matched on all its
     * columns; each table still refuses more than 10 000 rows.
     */
    public DbSnapshot watchSchema(String... excluded) {
        java.util.Set<String> skip = new java.util.HashSet<>();
        for (String table : excluded) {
            skip.add(table.toLowerCase(java.util.Locale.ROOT));
        }
        DataSource target = target();
        Map<String, TableSnapshot> tables = new LinkedHashMap<>();
        try (Connection connection = target.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            String schema = connection.getSchema();
            List<String> names = new ArrayList<>();
            try (ResultSet result = meta.getTables(connection.getCatalog(), schema, "%", new String[] {"TABLE"})) {
                while (result.next()) {
                    names.add(result.getString("TABLE_NAME"));
                }
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            for (String name : names) {
                if (skip.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                    continue;
                }
                List<String> key = primaryKey(meta, connection.getCatalog(), schema, name);
                TableQuery query = new TableQuery(target, name);
                query.keyedBy(key.isEmpty() ? List.of(columns(meta, connection.getCatalog(), schema, name)) : key);
                tables.put(name, query.take());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not list the tables to watch: " + e.getMessage(), e);
        }
        if (tables.isEmpty()) {
            throw new IllegalStateException("watchSchema() found no table to watch in the connection's schema");
        }
        return new DbSnapshot(tables);
    }

    private static List<String> primaryKey(DatabaseMetaData meta, String catalog, String schema, String table)
            throws SQLException {
        Map<Integer, String> ordered = new java.util.TreeMap<>();
        try (ResultSet result = meta.getPrimaryKeys(catalog, schema, table)) {
            while (result.next()) {
                ordered.put(result.getInt("KEY_SEQ"), result.getString("COLUMN_NAME"));
            }
        }
        return new ArrayList<>(ordered.values());
    }

    private static String[] columns(DatabaseMetaData meta, String catalog, String schema, String table)
            throws SQLException {
        List<String> columns = new ArrayList<>();
        try (ResultSet result = meta.getColumns(catalog, schema, table, "%")) {
            while (result.next()) {
                columns.add(result.getString("COLUMN_NAME"));
            }
        }
        return columns.toArray(String[]::new);
    }

    /** Rules the data must keep — no orphans, no duplicates, only known values; see {@link DbInvariants}. */
    public DbInvariants invariants() {
        return new DbInvariants(target());
    }

    /** Rows a test inserts for its own setup, removed after it. */
    public TableWriter into(String table) {
        return new TableWriter(target(), table);
    }

    private DataSource target() {
        return dataSource != null ? dataSource : registry.resolveDefault();
    }
}
