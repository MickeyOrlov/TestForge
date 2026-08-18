package io.testforge.db.contract.diff;

import io.testforge.db.contract.model.DbColumn;
import io.testforge.db.contract.model.DbForeignKey;
import io.testforge.db.contract.model.DbIndex;
import io.testforge.db.contract.model.DbPrimaryKey;
import io.testforge.db.contract.model.DbSchemaSnapshot;
import io.testforge.db.contract.model.DbTable;
import io.testforge.db.schema.ColumnTypeFamily;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Bounded comparator between two normalized schema snapshots.
 *
 * <p>It understands two already-normalized structures and nothing else: no SQL,
 * no migration scripts, no vendor dialects. Objects are matched by name — a
 * renamed table, column, index or constraint therefore reports as a removal
 * plus an addition, which is the honest reading of the two snapshots alone.
 *
 * <p>Adding or removing a whole table produces exactly one change; its columns
 * are not reported individually.
 */
public final class DbSchemaComparator {

    private DbSchemaComparator() {
    }

    /**
     * Compares two snapshots and returns the differences in deterministic order
     * (by table, then object, then change type).
     *
     * @param baseline the previously captured snapshot
     * @param current  the freshly captured snapshot
     * @return the structural differences, empty when the schemas match
     */
    public static List<DbChange> compare(DbSchemaSnapshot baseline, DbSchemaSnapshot current) {
        if (baseline == null || current == null) {
            throw new IllegalArgumentException("Both snapshots must be provided to compare them");
        }

        Map<String, DbTable> baselineTables = byName(baseline.tables(), DbTable::name);
        Map<String, DbTable> currentTables = byName(current.tables(), DbTable::name);
        List<DbChange> changes = new ArrayList<>();

        for (Map.Entry<String, DbTable> entry : currentTables.entrySet()) {
            DbTable previous = baselineTables.get(entry.getKey());
            if (previous == null) {
                changes.add(DbChange.ofTable(DbChangeType.TABLE_ADDED, entry.getKey(),
                        null, entry.getValue().columns().size() + " column(s)"));
            } else {
                compareTable(previous, entry.getValue(), changes);
            }
        }
        for (Map.Entry<String, DbTable> entry : baselineTables.entrySet()) {
            if (!currentTables.containsKey(entry.getKey())) {
                changes.add(DbChange.ofTable(DbChangeType.TABLE_REMOVED, entry.getKey(),
                        entry.getValue().columns().size() + " column(s)", null));
            }
        }

        changes.sort(Comparator.comparing(DbChange::table)
                .thenComparing(change -> change.object() == null ? "" : change.object())
                .thenComparing(change -> change.type().name()));
        return List.copyOf(changes);
    }

    private static void compareTable(DbTable baseline, DbTable current, List<DbChange> changes) {
        compareColumns(baseline, current, changes);
        comparePrimaryKey(baseline, current, changes);
        compareForeignKeys(baseline, current, changes);
        compareIndexes(baseline, current, changes);
    }

    private static void compareColumns(DbTable baseline, DbTable current, List<DbChange> changes) {
        Map<String, DbColumn> baselineColumns = byName(baseline.columns(), DbColumn::name);
        Map<String, DbColumn> currentColumns = byName(current.columns(), DbColumn::name);
        String table = current.name();

        for (Map.Entry<String, DbColumn> entry : currentColumns.entrySet()) {
            DbColumn now = entry.getValue();
            DbColumn before = baselineColumns.get(entry.getKey());
            if (before == null) {
                changes.add(new DbChange(DbChangeType.COLUMN_ADDED, table, now.name(), null, now.describe()));
                continue;
            }
            compareColumn(table, before, now, changes);
        }
        for (Map.Entry<String, DbColumn> entry : baselineColumns.entrySet()) {
            if (!currentColumns.containsKey(entry.getKey())) {
                changes.add(new DbChange(DbChangeType.COLUMN_REMOVED, table, entry.getKey(),
                        entry.getValue().describe(), null));
            }
        }
    }

    private static void compareColumn(String table, DbColumn before, DbColumn now, List<DbChange> changes) {
        String name = now.name();

        if (before.typeFamily() != now.typeFamily()) {
            changes.add(new DbChange(DbChangeType.COLUMN_TYPE_FAMILY_CHANGED, table, name,
                    describeType(before), describeType(now)));
        } else if (!before.type().equals(now.type())) {
            changes.add(new DbChange(DbChangeType.COLUMN_PHYSICAL_TYPE_CHANGED, table, name,
                    describeType(before), describeType(now)));
        }

        if (before.nullable() && !now.nullable()) {
            changes.add(new DbChange(DbChangeType.COLUMN_NULLABILITY_TIGHTENED, table, name,
                    "NULL", "NOT NULL"));
        } else if (!before.nullable() && now.nullable()) {
            changes.add(new DbChange(DbChangeType.COLUMN_NULLABILITY_RELAXED, table, name,
                    "NOT NULL", "NULL"));
        }

        if (!before.hasDefault() && now.hasDefault()) {
            changes.add(new DbChange(DbChangeType.COLUMN_DEFAULT_ADDED, table, name,
                    "no default", "default"));
        } else if (before.hasDefault() && !now.hasDefault()) {
            changes.add(new DbChange(DbChangeType.COLUMN_DEFAULT_REMOVED, table, name,
                    "default", "no default"));
        }
    }

    private static String describeType(DbColumn column) {
        String type = column.type().isBlank() ? column.typeFamily().name() : column.type();
        return column.typeFamily() == ColumnTypeFamily.UNKNOWN
                ? type + " [UNKNOWN]"
                : type + " [" + column.typeFamily().name() + "]";
    }

    private static void comparePrimaryKey(DbTable baseline, DbTable current, List<DbChange> changes) {
        DbPrimaryKey before = baseline.primaryKey();
        DbPrimaryKey now = current.primaryKey();
        String table = current.name();

        if (before == null && now == null) {
            return;
        }
        if (before == null) {
            changes.add(DbChange.ofTable(DbChangeType.PRIMARY_KEY_ADDED, table,
                    null, columns(now.columns())));
            return;
        }
        if (now == null) {
            changes.add(DbChange.ofTable(DbChangeType.PRIMARY_KEY_REMOVED, table,
                    columns(before.columns()), null));
            return;
        }
        // The constraint name is intentionally not compared: renaming a
        // primary-key constraint changes no consumer's contract.
        if (!before.columns().equals(now.columns())) {
            changes.add(DbChange.ofTable(DbChangeType.PRIMARY_KEY_COLUMNS_CHANGED, table,
                    columns(before.columns()), columns(now.columns())));
        }
    }

    private static void compareForeignKeys(DbTable baseline, DbTable current, List<DbChange> changes) {
        Map<String, DbForeignKey> baselineKeys = byName(baseline.foreignKeys(), DbForeignKey::name);
        Map<String, DbForeignKey> currentKeys = byName(current.foreignKeys(), DbForeignKey::name);
        String table = current.name();

        for (Map.Entry<String, DbForeignKey> entry : currentKeys.entrySet()) {
            DbForeignKey before = baselineKeys.get(entry.getKey());
            DbForeignKey now = entry.getValue();
            if (before == null) {
                changes.add(new DbChange(DbChangeType.FOREIGN_KEY_ADDED, table, now.name(),
                        null, now.describe()));
            } else if (!before.columns().equals(now.columns())
                    || !before.referencedTable().equals(now.referencedTable())
                    || !before.referencedColumns().equals(now.referencedColumns())) {
                changes.add(new DbChange(DbChangeType.FOREIGN_KEY_CHANGED, table, now.name(),
                        before.describe(), now.describe()));
            }
        }
        for (Map.Entry<String, DbForeignKey> entry : baselineKeys.entrySet()) {
            if (!currentKeys.containsKey(entry.getKey())) {
                changes.add(new DbChange(DbChangeType.FOREIGN_KEY_REMOVED, table, entry.getKey(),
                        entry.getValue().describe(), null));
            }
        }
    }

    private static void compareIndexes(DbTable baseline, DbTable current, List<DbChange> changes) {
        Map<String, DbIndex> baselineIndexes = byName(baseline.indexes(), DbIndex::name);
        Map<String, DbIndex> currentIndexes = byName(current.indexes(), DbIndex::name);
        String table = current.name();

        for (Map.Entry<String, DbIndex> entry : currentIndexes.entrySet()) {
            DbIndex before = baselineIndexes.get(entry.getKey());
            DbIndex now = entry.getValue();
            if (before == null) {
                changes.add(new DbChange(DbChangeType.INDEX_ADDED, table, now.name(), null, now.describe()));
                continue;
            }
            if (!before.columns().equals(now.columns())) {
                changes.add(new DbChange(DbChangeType.INDEX_COLUMNS_CHANGED, table, now.name(),
                        before.describe(), now.describe()));
            }
            if (!before.unique() && now.unique()) {
                changes.add(new DbChange(DbChangeType.INDEX_UNIQUENESS_TIGHTENED, table, now.name(),
                        "non-unique", "unique"));
            } else if (before.unique() && !now.unique()) {
                changes.add(new DbChange(DbChangeType.INDEX_UNIQUENESS_RELAXED, table, now.name(),
                        "unique", "non-unique"));
            }
        }
        for (Map.Entry<String, DbIndex> entry : baselineIndexes.entrySet()) {
            if (!currentIndexes.containsKey(entry.getKey())) {
                changes.add(new DbChange(DbChangeType.INDEX_REMOVED, table, entry.getKey(),
                        entry.getValue().describe(), null));
            }
        }
    }

    private static String columns(List<String> columns) {
        return "(" + String.join(", ", columns) + ")";
    }

    private static <T> Map<String, T> byName(List<T> values, Function<T, String> name) {
        Map<String, T> byName = new TreeMap<>();
        for (T value : values) {
            byName.put(name.apply(value), value);
        }
        return new LinkedHashMap<>(byName);
    }
}
