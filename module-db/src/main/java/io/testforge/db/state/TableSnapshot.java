package io.testforge.db.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The rows of a {@link TableQuery} at one moment. {@link #diff()} reads them
 * again and says what was added, removed and changed — per column.
 */
public final class TableSnapshot {

    private final TableQuery query;
    private final Map<List<Object>, Map<String, Object>> rows;

    TableSnapshot(TableQuery query, Map<List<Object>, Map<String, Object>> rows) {
        this.query = query;
        this.rows = rows;
    }

    public int size() {
        return rows.size();
    }

    /** Runs an action that must fail, then requires these rows to be as they were; returns what it threw. */
    public Throwable expectRolledBack(DbAction action) {
        return Rollback.expect(action, this::diff, () -> diff().isEmpty());
    }

    /** These rows are as they were — for a rejection that did not throw. */
    public void assertUnchanged() {
        diff().expect().nothing();
    }

    /** These rows are back as they were within {@code timeout}: a compensation undid the change. */
    public void assertUnchangedWithin(java.time.Duration timeout) {
        Rollback.within(timeout, this::diff, () -> diff().isEmpty());
    }

    /** Reads the same rows again and compares. */
    public TableDiff diff() {
        Map<List<Object>, Map<String, Object>> now = query.read();
        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> removed = new ArrayList<>();
        List<TableDiff.RowChange> changed = new ArrayList<>();
        // rows where only ignored columns moved: not changes, but row(...) can still look at them
        List<TableDiff.RowChange> touched = new ArrayList<>();

        now.forEach((key, row) -> {
            Map<String, Object> before = rows.get(key);
            if (before == null) {
                added.add(row);
                return;
            }
            Map<String, TableDiff.ValueChange> columns = new LinkedHashMap<>();
            Map<String, TableDiff.ValueChange> ignored = new LinkedHashMap<>();
            row.forEach((column, value) -> {
                if (!Objects.equals(before.get(column), value)) {
                    // an ignored column does not make a row "changed", but its move is kept for row(...) checks
                    (query.ignored().contains(column) ? ignored : columns)
                            .put(column, new TableDiff.ValueChange(before.get(column), value));
                }
            });
            if (!columns.isEmpty()) {
                changed.add(new TableDiff.RowChange(key(key), columns, ignored, row));
            } else if (!ignored.isEmpty()) {
                touched.add(new TableDiff.RowChange(key(key), Map.of(), ignored, row));
            }
        });
        rows.forEach((key, row) -> {
            if (!now.containsKey(key)) {
                removed.add(row);
            }
        });
        return new TableDiff(query.describe(), query.key(), added, removed, changed, touched);
    }

    private Map<String, Object> key(List<Object> values) {
        Map<String, Object> key = new LinkedHashMap<>();
        for (int i = 0; i < query.key().size(); i++) {
            key.put(query.key().get(i), values.get(i));
        }
        return key;
    }
}
