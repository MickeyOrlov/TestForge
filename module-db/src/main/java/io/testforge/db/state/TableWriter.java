package io.testforge.db.state;

import io.testforge.data.cleanup.Cleanup;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import javax.sql.DataSource;

/**
 * Rows a test puts in place itself, removed after it — even when it fails —
 * through {@code module-data}'s {@link Cleanup}:
 *
 * <pre>{@code
 * Map<String, Object> key = db.into("orders").keyedBy("id")
 *         .insert(Map.of("customer_id", customerId, "status", "NEW"));
 * }</pre>
 *
 * The key is taken from the values given, or else from the keys the database
 * generated. The delete is registered the moment the insert succeeds, newest
 * first like every cleanup, so a child row inserted after its parent goes
 * first. Prefer the product's API for setup; this is for state the API cannot
 * produce, or cannot produce fast.
 */
public final class TableWriter {

    private final DataSource dataSource;
    private final String table;
    private final List<String> key = new ArrayList<>();
    private boolean cleanup = true;

    TableWriter(DataSource dataSource, String table) {
        this.dataSource = dataSource;
        this.table = TableQuery.identifier(table, "table");
    }

    /** The columns that identify an inserted row for its delete. Required. */
    public TableWriter keyedBy(String... columns) {
        key.clear();
        for (String column : columns) {
            key.add(TableQuery.identifier(column, "key column").toLowerCase(Locale.ROOT));
        }
        return this;
    }

    /** Leave the rows in place: for data the next tests of the class rely on, removed by hand. */
    public TableWriter withoutCleanup() {
        this.cleanup = false;
        return this;
    }

    /** Inserts one row and returns its key, column to value. */
    public Map<String, Object> insert(Map<String, ?> values) {
        if (key.isEmpty()) {
            throw new IllegalStateException("Insert into " + table + " needs keyedBy(...) to remove the row later");
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Insert into " + table + " needs at least one column");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        values.forEach((column, value) ->
                row.put(TableQuery.identifier(column, "column").toLowerCase(Locale.ROOT), value));

        StringJoiner columns = new StringJoiner(", ");
        StringJoiner marks = new StringJoiner(", ");
        row.keySet().forEach(column -> {
            columns.add(column);
            marks.add("?");
        });
        String sql = "INSERT INTO " + table + " (" + columns + ") VALUES (" + marks + ")";
        Map<String, Object> id = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            int index = 1;
            for (Object value : row.values()) {
                statement.setObject(index++, value);
            }
            statement.executeUpdate();
            Map<String, Object> generated = generated(statement);
            for (String column : key) {
                if (row.containsKey(column)) {
                    id.put(column, row.get(column));
                } else if (generated.containsKey(column)) {
                    id.put(column, generated.get(column));
                } else {
                    throw new IllegalStateException("Inserted into " + table + " but cannot tell its " + column
                            + ": not among the values given, and the database generated " + generated.keySet()
                            + ". The row stays in place.");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not insert into " + table + ": " + e.getMessage(), e);
        }
        if (cleanup) {
            Map<String, Object> rowKey = Map.copyOf(id);
            Cleanup.register(table + " " + describe(id), () -> delete(rowKey));
        }
        return id;
    }

    private static Map<String, Object> generated(PreparedStatement statement) throws SQLException {
        Map<String, Object> generated = new LinkedHashMap<>();
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (keys != null && keys.next()) {
                ResultSetMetaData meta = keys.getMetaData();
                for (int column = 1; column <= meta.getColumnCount(); column++) {
                    generated.put(meta.getColumnLabel(column).toLowerCase(Locale.ROOT), keys.getObject(column));
                }
            }
        }
        return generated;
    }

    private void delete(Map<String, Object> id) throws SQLException {
        StringJoiner where = new StringJoiner(" AND ");
        key.forEach(column -> where.add(column + " = ?"));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE " + where)) {
            int index = 1;
            for (String column : key) {
                statement.setObject(index++, id.get(column));
            }
            statement.executeUpdate();
        }
    }

    private static String describe(Map<String, Object> id) {
        StringJoiner out = new StringJoiner(",");
        id.forEach((column, value) -> out.add(column + "=" + value));
        return out.toString();
    }
}
