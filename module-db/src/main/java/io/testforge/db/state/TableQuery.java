package io.testforge.db.state;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * Which rows of one table a snapshot covers, and how a row is identified.
 *
 * <p>On a shared database, narrow it to the test's own data with
 * {@link #where}: a snapshot of the whole table sees every parallel test's
 * writes as changes. The where clause is the test's own SQL; the table and
 * column names are checked to be plain identifiers.
 */
public final class TableQuery {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");

    static final int DEFAULT_MAX_ROWS = 10_000;

    private final DataSource dataSource;
    private final String table;
    private final List<String> key = new ArrayList<>();
    private final Set<String> ignored = new LinkedHashSet<>();
    private String where;
    private Object[] parameters = new Object[0];
    private int maxRows = DEFAULT_MAX_ROWS;

    TableQuery(DataSource dataSource, String table) {
        this.dataSource = dataSource;
        this.table = identifier(table, "table");
    }

    /** The columns that identify a row; one or more. Required. */
    public TableQuery keyedBy(java.util.Collection<String> columns) {
        return keyedBy(columns.toArray(String[]::new));
    }

    /** The columns that identify a row; one or more. Required. */
    public TableQuery keyedBy(String... columns) {
        key.clear();
        for (String column : columns) {
            key.add(identifier(column, "key column").toLowerCase(Locale.ROOT));
        }
        return this;
    }

    /** Only these rows, e.g. {@code where("customer_id = ?", id)}. */
    public TableQuery where(String condition, Object... parameters) {
        this.where = condition;
        this.parameters = parameters == null ? new Object[0] : parameters.clone();
        return this;
    }

    /** Columns whose changes do not count, e.g. {@code updated_at} or a version counter. */
    public TableQuery ignoring(String... columns) {
        for (String column : columns) {
            ignored.add(identifier(column, "ignored column").toLowerCase(Locale.ROOT));
        }
        return this;
    }

    /** Refuse to snapshot more rows than this (default 10 000): a missing {@code where} is a mistake. */
    public TableQuery maxRows(int maxRows) {
        if (maxRows <= 0) {
            throw new IllegalArgumentException("maxRows must be positive");
        }
        this.maxRows = maxRows;
        return this;
    }

    public TableSnapshot take() {
        if (key.isEmpty()) {
            throw new IllegalStateException("Snapshot of " + table + " needs keyedBy(...): rows are matched by key");
        }
        return new TableSnapshot(this, read());
    }

    String describe() {
        return table + (where == null ? "" : " where " + where) + " (keyed by " + String.join(", ", key) + ")";
    }

    String table() {
        return table;
    }

    List<String> key() {
        return key;
    }

    Set<String> ignored() {
        return ignored;
    }

    /** Rows by key, columns lowercased, in key order of the query. */
    Map<List<Object>, Map<String, Object>> read() {
        String sql = "SELECT * FROM " + table + (where == null ? "" : " WHERE " + where);
        Map<List<Object>, Map<String, Object>> rows = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.setMaxRows(maxRows + 1);
            try (ResultSet result = statement.executeQuery()) {
                ResultSetMetaData meta = result.getMetaData();
                while (result.next()) {
                    if (rows.size() == maxRows) {
                        throw new IllegalStateException("Snapshot of " + describe() + " has more than " + maxRows
                                + " rows; narrow it with where(...) or raise maxRows(...)");
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int column = 1; column <= meta.getColumnCount(); column++) {
                        row.put(meta.getColumnLabel(column).toLowerCase(Locale.ROOT), normalize(result.getObject(column)));
                    }
                    List<Object> id = new ArrayList<>();
                    for (String column : key) {
                        if (!row.containsKey(column)) {
                            throw new IllegalStateException("Key column " + column + " is not a column of " + table
                                    + "; columns: " + row.keySet());
                        }
                        id.add(row.get(column));
                    }
                    rows.put(List.copyOf(id), row);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read " + describe() + ": " + e.getMessage(), e);
        }
        return rows;
    }

    /** Values that compare by meaning: {@code 10.0} equals {@code 10.00}, LOB-free scalars only. */
    private static Object normalize(Object value) {
        if (value instanceof BigDecimal decimal) {
            // 10.00 and 10.0 are one value; plain so a report says 10, not 1E+1
            return new BigDecimal(decimal.stripTrailingZeros().toPlainString());
        }
        if (value instanceof byte[] bytes) {
            return java.util.HexFormat.of().formatHex(bytes);
        }
        if (value instanceof java.sql.Clob || value instanceof java.sql.Blob) {
            return "<lob>";
        }
        return value;
    }

    static String identifier(String name, String what) {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("'" + name + "' is not a plain " + what + " name");
        }
        return name;
    }
}
