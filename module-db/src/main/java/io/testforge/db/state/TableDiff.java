package io.testforge.db.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What happened to a table between a snapshot and now.
 *
 * <p>{@link #toString()} is the readable form every failed expectation
 * carries:
 *
 * <pre>
 * orders where customer_id = ? (keyed by id)
 *   + id=42 {status=NEW, total=10}
 *   ~ id=41 status: NEW → PAID
 *   - id=40 {status=CANCELLED, total=5}
 * </pre>
 */
public record TableDiff(
        String table,
        List<String> key,
        List<Map<String, Object>> added,
        List<Map<String, Object>> removed,
        List<RowChange> changed) {

    public TableDiff {
        key = List.copyOf(key);
        added = Collections.unmodifiableList(new ArrayList<>(added));
        removed = Collections.unmodifiableList(new ArrayList<>(removed));
        changed = List.copyOf(changed);
    }

    /** One column, before and after. Either side may be {@code null}. */
    public record ValueChange(Object before, Object after) {
    }

    /** One row that is in both snapshots, with the columns that differ. */
    public record RowChange(Map<String, Object> key, Map<String, ValueChange> columns) {

        public RowChange {
            key = Collections.unmodifiableMap(new LinkedHashMap<>(key));
            columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
        }
    }

    public boolean isEmpty() {
        return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
    }

    /** Every column that changed in any row. */
    public Set<String> changedColumns() {
        Set<String> columns = new TreeSet<>();
        changed.forEach(change -> columns.addAll(change.columns().keySet()));
        return columns;
    }

    /** Fluent expectations that fail with the whole diff in the message. */
    public Expectation expect() {
        return new Expectation(this);
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder(table);
        if (isEmpty()) {
            return out.append("\n  (no changes)").toString();
        }
        added.forEach(row -> out.append("\n  + ").append(keyOf(row)).append(' ').append(rest(row)));
        changed.forEach(change -> {
            out.append("\n  ~ ").append(format(change.key()));
            change.columns().forEach((column, value) -> out.append(' ').append(column).append(": ")
                    .append(value.before()).append(" → ").append(value.after()).append(';'));
            out.setLength(out.length() - 1);
        });
        removed.forEach(row -> out.append("\n  - ").append(keyOf(row)).append(' ').append(rest(row)));
        return out.toString();
    }

    private String keyOf(Map<String, Object> row) {
        Map<String, Object> id = new LinkedHashMap<>();
        key.forEach(column -> id.put(column, row.get(column)));
        return format(id);
    }

    private Map<String, Object> rest(Map<String, Object> row) {
        Map<String, Object> rest = new LinkedHashMap<>(row);
        key.forEach(rest::remove);
        return rest;
    }

    private static String format(Map<String, Object> key) {
        StringBuilder out = new StringBuilder();
        key.forEach((column, value) -> out.append(out.isEmpty() ? "" : ",").append(column).append('=').append(value));
        return out.toString();
    }

    /** Checks run as they are called; the first that fails throws with the full diff. */
    public static final class Expectation {

        private final TableDiff diff;

        private Expectation(TableDiff diff) {
            this.diff = diff;
        }

        public Expectation added(int rows) {
            return check(diff.added.size() == rows, "expected " + rows + " added rows, got " + diff.added.size());
        }

        public Expectation removed(int rows) {
            return check(diff.removed.size() == rows, "expected " + rows + " removed rows, got " + diff.removed.size());
        }

        /**
         * Exactly {@code rows} rows changed, and only in these columns. Changes
         * in any other column fail: that is the neighbouring-update bug.
         */
        public Expectation changed(int rows, String... onlyColumns) {
            check(diff.changed.size() == rows, "expected " + rows + " changed rows, got " + diff.changed.size());
            if (onlyColumns.length > 0) {
                Set<String> allowed = new LinkedHashSet<>();
                for (String column : onlyColumns) {
                    allowed.add(column.toLowerCase(java.util.Locale.ROOT));
                }
                Set<String> unexpected = new TreeSet<>(diff.changedColumns());
                unexpected.removeAll(allowed);
                check(unexpected.isEmpty(), "columns changed that should not have: " + unexpected);
            }
            return this;
        }

        /** Nothing at all happened to these rows. */
        public Expectation nothing() {
            return check(diff.isEmpty(), "expected no changes");
        }

        private Expectation check(boolean ok, String message) {
            if (!ok) {
                throw new AssertionError(message + " in " + diff);
            }
            return this;
        }
    }
}
