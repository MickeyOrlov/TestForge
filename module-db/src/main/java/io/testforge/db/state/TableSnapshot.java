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

    /** Reads the same rows again and compares. */
    public TableDiff diff() {
        Map<List<Object>, Map<String, Object>> now = query.read();
        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> removed = new ArrayList<>();
        List<TableDiff.RowChange> changed = new ArrayList<>();

        now.forEach((key, row) -> {
            Map<String, Object> before = rows.get(key);
            if (before == null) {
                added.add(row);
                return;
            }
            Map<String, TableDiff.ValueChange> columns = new LinkedHashMap<>();
            row.forEach((column, value) -> {
                if (!query.ignored().contains(column) && !Objects.equals(before.get(column), value)) {
                    columns.put(column, new TableDiff.ValueChange(before.get(column), value));
                }
            });
            if (!columns.isEmpty()) {
                changed.add(new TableDiff.RowChange(key(key), columns));
            }
        });
        rows.forEach((key, row) -> {
            if (!now.containsKey(key)) {
                removed.add(row);
            }
        });
        return new TableDiff(query.describe(), query.key(), added, removed, changed);
    }

    private Map<String, Object> key(List<Object> values) {
        Map<String, Object> key = new LinkedHashMap<>();
        for (int i = 0; i < query.key().size(); i++) {
            key.put(query.key().get(i), values.get(i));
        }
        return key;
    }
}
