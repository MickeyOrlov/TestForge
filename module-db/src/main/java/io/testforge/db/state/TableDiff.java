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
        List<RowChange> changed,
        List<RowChange> touched) {

    public TableDiff {
        key = List.copyOf(key);
        added = Collections.unmodifiableList(new ArrayList<>(added));
        removed = Collections.unmodifiableList(new ArrayList<>(removed));
        changed = List.copyOf(changed);
        touched = touched == null ? List.of() : List.copyOf(touched);
    }

    public TableDiff(String table, List<String> key, List<Map<String, Object>> added,
                     List<Map<String, Object>> removed, List<RowChange> changed) {
        this(table, key, added, removed, changed, List.of());
    }

    /** One column, before and after. Either side may be {@code null}. */
    public record ValueChange(Object before, Object after) {
    }

    /**
     * One row that is in both snapshots, with the columns that differ.
     *
     * @param ignored moves in {@code ignoring(...)} columns: they never make a
     *                row count as changed, but {@link Expectation#row} can check them
     */
    public record RowChange(Map<String, Object> key, Map<String, ValueChange> columns,
                            Map<String, ValueChange> ignored) {

        public RowChange {
            key = Collections.unmodifiableMap(new LinkedHashMap<>(key));
            columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
            ignored = ignored == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(ignored));
        }

        public RowChange(Map<String, Object> key, Map<String, ValueChange> columns) {
            this(key, columns, Map.of());
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

        /**
         * Expectations on one row that was in the snapshot, by its key values in
         * {@code keyedBy} order: {@code row(42)}, {@code row(7, "EUR")}.
         *
         * <pre>{@code
         * diff.expect().changed(1)
         *         .row(42)
         *             .changed("status", "NEW", "PAID")
         *             .increasedBy("version", 1)
         *             .movedForward("updated_at")
         *             .unchanged("created_at", "customer_id")
         *             .onlyThese();
         * }</pre>
         */
        public RowExpectation row(Object... keyValues) {
            Map<String, Object> key = keyOf(keyValues);
            for (RowChange change : diff.changed) {
                if (sameKey(change.key(), key)) {
                    return new RowExpectation(this, key, change);
                }
            }
            for (RowChange change : diff.touched) {
                if (sameKey(change.key(), key)) {
                    return new RowExpectation(this, key, change);
                }
            }
            for (Map<String, Object> row : diff.added) {
                check(!sameKey(keyColumns(row), key), "row " + format(key) + " was added, not changed; use addedRow(...)");
            }
            for (Map<String, Object> row : diff.removed) {
                check(!sameKey(keyColumns(row), key), "row " + format(key) + " was removed");
            }
            return new RowExpectation(this, key, new RowChange(key, Map.of(), Map.of()));
        }

        /** Expectations on a row that appeared, by its key values. */
        public AddedRowExpectation addedRow(Object... keyValues) {
            Map<String, Object> key = keyOf(keyValues);
            for (Map<String, Object> row : diff.added) {
                if (sameKey(keyColumns(row), key)) {
                    return new AddedRowExpectation(this, row);
                }
            }
            throw new AssertionError("expected row " + format(key) + " to be added in " + diff);
        }

        /** The row with these key values disappeared. */
        public Expectation removedRow(Object... keyValues) {
            Map<String, Object> key = keyOf(keyValues);
            return check(diff.removed.stream().anyMatch(row -> sameKey(keyColumns(row), key)),
                    "expected row " + format(key) + " to be removed");
        }

        private Map<String, Object> keyOf(Object... values) {
            if (values == null || values.length != diff.key.size()) {
                throw new IllegalArgumentException("a row is identified by " + diff.key.size() + " key value(s) "
                        + diff.key + ", got " + (values == null ? 0 : values.length));
            }
            Map<String, Object> key = new LinkedHashMap<>();
            for (int i = 0; i < values.length; i++) {
                key.put(diff.key.get(i), values[i]);
            }
            return key;
        }

        private Map<String, Object> keyColumns(Map<String, Object> row) {
            Map<String, Object> key = new LinkedHashMap<>();
            diff.key.forEach(column -> key.put(column, row.get(column)));
            return key;
        }

        private static boolean sameKey(Map<String, Object> actual, Map<String, Object> expected) {
            for (Map.Entry<String, Object> entry : expected.entrySet()) {
                if (!Values.same(entry.getValue(), actual.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }

        private Expectation check(boolean ok, String message) {
            if (!ok) {
                throw new AssertionError(message + " in " + diff);
            }
            return this;
        }
    }

    /**
     * What happened to one row, column by column. Every check fails with the
     * whole diff; {@link #and()} goes back to the table's expectations.
     */
    public static final class RowExpectation {

        private final Expectation parent;
        private final String row;
        private final RowChange change;
        private final Set<String> named = new LinkedHashSet<>();

        private RowExpectation(Expectation parent, Map<String, Object> key, RowChange change) {
            this.parent = parent;
            this.row = format(key);
            this.change = change;
        }

        /** The column moved from {@code before} to {@code after}. */
        public RowExpectation changed(String column, Object before, Object after) {
            ValueChange value = move(column);
            return check(Values.same(before, value.before()) && Values.same(after, value.after()),
                    column + " expected " + before + " → " + after + ", was " + value.before() + " → " + value.after());
        }

        /** The column moved to {@code after}, from whatever it was. */
        public RowExpectation changedTo(String column, Object after) {
            ValueChange value = move(column);
            return check(Values.same(after, value.after()),
                    column + " expected to become " + after + ", became " + value.after());
        }

        /** A numeric column grew by exactly {@code delta} — a version counter, a balance. */
        public RowExpectation increasedBy(String column, Number delta) {
            ValueChange value = move(column);
            java.math.BigDecimal before = Values.number(value.before(), column);
            java.math.BigDecimal after = Values.number(value.after(), column);
            return check(after.subtract(before).compareTo(Values.number(delta, column)) == 0,
                    column + " expected to increase by " + delta + ", went " + value.before() + " → " + value.after());
        }

        /** A timestamp or counter moved forward — {@code updated_at} after an update. */
        public RowExpectation movedForward(String column) {
            ValueChange value = move(column);
            return check(Values.compare(value.after(), value.before(), column) > 0,
                    column + " expected to move forward, went " + value.before() + " → " + value.after());
        }

        /** These columns kept their value — {@code created_at}, the owner, the tenant. */
        public RowExpectation unchanged(String... columns) {
            for (String column : columns) {
                String name = column.toLowerCase(java.util.Locale.ROOT);
                named.add(name);
                ValueChange value = change.columns().containsKey(name) ? change.columns().get(name)
                        : change.ignored().get(name);
                check(value == null, name + " expected unchanged, went "
                        + (value == null ? "" : value.before() + " → " + value.after()));
            }
            return this;
        }

        /**
         * No column of this row changed except those named in this row's checks
         * (ignored columns aside) — the field an update should not have touched.
         */
        public RowExpectation onlyThese() {
            Set<String> unexpected = new TreeSet<>(change.columns().keySet());
            unexpected.removeAll(named);
            return check(unexpected.isEmpty(), "columns changed that the checks did not name: " + unexpected);
        }

        /** Back to the table's expectations. */
        public Expectation and() {
            return parent;
        }

        private ValueChange move(String column) {
            String name = column.toLowerCase(java.util.Locale.ROOT);
            named.add(name);
            ValueChange value = change.columns().containsKey(name) ? change.columns().get(name)
                    : change.ignored().get(name);
            if (value == null) {
                check(false, name + " did not change");
            }
            return value;
        }

        private RowExpectation check(boolean ok, String message) {
            if (!ok) {
                parent.check(false, "row " + row + ": " + message);
            }
            return this;
        }
    }

    /** What a row that appeared holds. */
    public static final class AddedRowExpectation {

        private final Expectation parent;
        private final Map<String, Object> row;

        private AddedRowExpectation(Expectation parent, Map<String, Object> row) {
            this.parent = parent;
            this.row = row;
        }

        /** The new row has this value in this column. */
        public AddedRowExpectation has(String column, Object value) {
            String name = column.toLowerCase(java.util.Locale.ROOT);
            if (!row.containsKey(name)) {
                parent.check(false, "added row has no column " + name + "; columns: " + row.keySet());
            }
            if (!Values.same(value, row.get(name))) {
                parent.check(false, "added row " + name + " expected " + value + ", was " + row.get(name));
            }
            return this;
        }

        public Expectation and() {
            return parent;
        }
    }

    /** Values from JDBC compared by meaning: {@code 42} matches a Long, an Integer and a NUMERIC 42. */
    static final class Values {

        private Values() {
        }

        static boolean same(Object expected, Object actual) {
            if (expected == null || actual == null) {
                return expected == actual;
            }
            if (expected instanceof Number && actual instanceof Number) {
                return number(expected, "value").compareTo(number(actual, "value")) == 0;
            }
            if (expected instanceof Enum<?> e) {
                return e.name().equals(String.valueOf(actual));
            }
            if (expected instanceof Character || expected instanceof CharSequence) {
                return expected.toString().equals(String.valueOf(actual));
            }
            return expected.equals(actual) || temporalSame(expected, actual);
        }

        static java.math.BigDecimal number(Object value, String column) {
            if (value instanceof java.math.BigDecimal decimal) {
                return decimal;
            }
            if (value instanceof Number number) {
                return new java.math.BigDecimal(number.toString());
            }
            throw new AssertionError(column + " is not numeric: " + value);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        static int compare(Object after, Object before, String column) {
            if (after == null || before == null) {
                throw new AssertionError(column + " cannot be compared: " + before + " → " + after);
            }
            if (after instanceof Number && before instanceof Number) {
                return number(after, column).compareTo(number(before, column));
            }
            if (after instanceof Comparable comparable && after.getClass().isInstance(before)) {
                return comparable.compareTo(before);
            }
            throw new AssertionError(column + " cannot be compared: " + before + " → " + after);
        }

        /** A {@code java.sql.Timestamp} from the driver against a {@code LocalDateTime} in the test. */
        private static boolean temporalSame(Object expected, Object actual) {
            if (actual instanceof java.sql.Timestamp timestamp && expected instanceof java.time.LocalDateTime local) {
                return timestamp.toLocalDateTime().equals(local);
            }
            if (actual instanceof java.sql.Date date && expected instanceof java.time.LocalDate local) {
                return date.toLocalDate().equals(local);
            }
            return false;
        }
    }
}
