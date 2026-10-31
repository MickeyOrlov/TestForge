package io.testforge.db.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** What happened to every watched table; {@link #toString()} shows the tables that moved. */
public final class DbDiff {

    private final Map<String, TableDiff> tables;

    DbDiff(Map<String, TableDiff> tables) {
        this.tables = Collections.unmodifiableMap(new LinkedHashMap<>(tables));
    }

    /** One table's diff, by the name it was watched under (case ignored). */
    public TableDiff table(String name) {
        TableDiff diff = find(name);
        if (diff == null) {
            throw new IllegalArgumentException("'" + name + "' is not a watched table; watched: " + tables.keySet());
        }
        return diff;
    }

    /** The watched tables in which anything moved. */
    public List<String> changedTables() {
        List<String> changed = new ArrayList<>();
        tables.forEach((name, diff) -> {
            if (!diff.isEmpty()) {
                changed.add(name);
            }
        });
        return changed;
    }

    public boolean isEmpty() {
        return changedTables().isEmpty();
    }

    public Expectation expect() {
        return new Expectation(this);
    }

    @Override
    public String toString() {
        List<String> changed = changedTables();
        if (changed.isEmpty()) {
            return "no watched table changed (" + String.join(", ", tables.keySet()) + ")";
        }
        StringBuilder out = new StringBuilder();
        changed.forEach(name -> out.append(out.isEmpty() ? "" : "\n").append(tables.get(name)));
        return out.toString();
    }

    private TableDiff find(String name) {
        for (Map.Entry<String, TableDiff> entry : tables.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Per-table expectations, then what may not have moved at all. */
    public static final class Expectation {

        private final DbDiff diff;
        private final Set<String> named = new LinkedHashSet<>();

        private Expectation(DbDiff diff) {
            this.diff = diff;
        }

        /** Expectations on one watched table; the table counts as named for {@link #nothingElse()}. */
        public Expectation table(String name, Consumer<TableDiff.Expectation> expectations) {
            TableDiff table = diff.table(name);
            named.add(name.toLowerCase(Locale.ROOT));
            expectations.accept(table.expect());
            return this;
        }

        /** No watched table moved except these. */
        public Expectation onlyChanged(String... names) {
            Set<String> allowed = new LinkedHashSet<>();
            for (String name : names) {
                diff.table(name);
                allowed.add(name.toLowerCase(Locale.ROOT));
            }
            return unexpected(allowed, "tables changed that should not have: ");
        }

        /** No watched table moved except those given expectations through {@link #table}. */
        public Expectation nothingElse() {
            return unexpected(named, "tables changed that the checks did not name: ");
        }

        private Expectation unexpected(Set<String> allowed, String message) {
            List<String> unexpected = diff.changedTables().stream()
                    .filter(name -> !allowed.contains(name.toLowerCase(Locale.ROOT)))
                    .toList();
            if (!unexpected.isEmpty()) {
                StringBuilder out = new StringBuilder(message).append(unexpected);
                unexpected.forEach(name -> out.append('\n').append(diff.table(name)));
                throw new AssertionError(out.toString());
            }
            return this;
        }
    }
}
