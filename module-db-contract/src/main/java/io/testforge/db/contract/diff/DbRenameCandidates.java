package io.testforge.db.contract.diff;

import io.testforge.db.contract.model.DbColumn;
import io.testforge.db.contract.model.DbForeignKey;
import io.testforge.db.contract.model.DbIndex;
import io.testforge.db.contract.model.DbSchemaSnapshot;
import io.testforge.db.contract.model.DbTable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Renames the comparator cannot see but a reader should be told about.
 *
 * <p>Objects are matched by name, so a renamed column reads as one removal and
 * one addition — and the removal is {@code BREAKING}. That verdict stays: a
 * consumer selecting the old name does fail. What a person reviewing the report
 * needs on top is the likely story, so a removed object and an added one in
 * the same place with an identical shape are paired here as a candidate.
 *
 * <p>Only unambiguous pairs are offered: when two removed columns share a
 * shape with one added column, nothing is guessed.
 */
public final class DbRenameCandidates {

    /** What kind of object may have been renamed. */
    public enum Kind { TABLE, COLUMN, INDEX, FOREIGN_KEY }

    /**
     * @param kind  the object kind
     * @param table the table holding it; for a table rename, the new name
     * @param from  the name in the baseline
     * @param to    the name in the current schema
     * @param shape what both have in common, the reason for pairing them
     */
    public record Candidate(Kind kind, String table, String from, String to, String shape) {

        /** {@code orders.amount -> orders.total}, or {@code orders -> purchases} for a table. */
        public String describe() {
            return kind == Kind.TABLE ? from + " -> " + to : table + "." + from + " -> " + table + "." + to;
        }
    }

    private DbRenameCandidates() {
    }

    /**
     * @return candidates ordered by table, then kind, then old name
     */
    public static List<Candidate> find(DbSchemaSnapshot baseline, DbSchemaSnapshot current) {
        Map<String, DbTable> before = byName(baseline.tables(), DbTable::name);
        Map<String, DbTable> after = byName(current.tables(), DbTable::name);
        List<Candidate> candidates = new ArrayList<>();

        Map<String, DbTable> removedTables = new LinkedHashMap<>(before);
        removedTables.keySet().removeAll(after.keySet());
        Map<String, DbTable> addedTables = new LinkedHashMap<>(after);
        addedTables.keySet().removeAll(before.keySet());
        pair(removedTables, addedTables, DbRenameCandidates::tableShape, (from, to, shape) ->
                candidates.add(new Candidate(Kind.TABLE, to, from, to, shape)));

        for (Map.Entry<String, DbTable> entry : after.entrySet()) {
            DbTable old = before.get(entry.getKey());
            if (old == null) {
                continue;
            }
            String table = entry.getKey();
            DbTable now = entry.getValue();
            pair(removed(old.columns(), now.columns(), DbColumn::name), removed(now.columns(), old.columns(),
                    DbColumn::name), DbColumn::describe, (from, to, shape) ->
                    candidates.add(new Candidate(Kind.COLUMN, table, from, to, shape)));
            pair(removed(old.indexes(), now.indexes(), DbIndex::name), removed(now.indexes(), old.indexes(),
                    DbIndex::name), DbRenameCandidates::indexShape, (from, to, shape) ->
                    candidates.add(new Candidate(Kind.INDEX, table, from, to, shape)));
            pair(removed(old.foreignKeys(), now.foreignKeys(), DbForeignKey::name),
                    removed(now.foreignKeys(), old.foreignKeys(), DbForeignKey::name), DbForeignKey::describe,
                    (from, to, shape) -> candidates.add(new Candidate(Kind.FOREIGN_KEY, table, from, to, shape)));
        }
        candidates.sort(Comparator.comparing(Candidate::table)
                .thenComparing(Candidate::kind)
                .thenComparing(Candidate::from));
        return List.copyOf(candidates);
    }

    private interface Pairing {
        void accept(String from, String to, String shape);
    }

    /** Pairs a removed and an added object whose shape no other removed or added object shares. */
    private static <T> void pair(
            Map<String, T> removed, Map<String, T> added, Function<T, String> shape, Pairing pairing) {
        if (removed.isEmpty() || added.isEmpty()) {
            return;
        }
        Map<String, List<String>> removedByShape = groupByShape(removed, shape);
        Map<String, List<String>> addedByShape = groupByShape(added, shape);
        removedByShape.forEach((key, names) -> {
            List<String> matches = addedByShape.get(key);
            if (names.size() == 1 && matches != null && matches.size() == 1) {
                pairing.accept(names.getFirst(), matches.getFirst(), key);
            }
        });
    }

    private static <T> Map<String, List<String>> groupByShape(Map<String, T> objects, Function<T, String> shape) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        objects.forEach((name, object) -> grouped.computeIfAbsent(shape.apply(object), ignored -> new ArrayList<>())
                .add(name));
        return grouped;
    }

    private static <T> Map<String, T> removed(List<T> from, List<T> in, Function<T, String> name) {
        Map<String, T> present = byName(in, name);
        Map<String, T> missing = new LinkedHashMap<>();
        from.forEach(object -> {
            if (!present.containsKey(name.apply(object))) {
                missing.put(name.apply(object), object);
            }
        });
        return missing;
    }

    private static String tableShape(DbTable table) {
        return table.columns().stream().map(column -> column.name() + " " + column.describe())
                .collect(Collectors.joining(", ", table.columns().size() + " columns: ", ""));
    }

    private static String indexShape(DbIndex index) {
        return (index.unique() ? "unique " : "") + "(" + String.join(", ", index.columns()) + ")"
                + (index.predicate() == null || index.predicate().isBlank() ? "" : " WHERE " + index.predicate());
    }

    private static <T> Map<String, T> byName(List<T> values, Function<T, String> name) {
        Map<String, T> map = new LinkedHashMap<>();
        values.forEach(value -> map.put(name.apply(value), value));
        return map;
    }
}
