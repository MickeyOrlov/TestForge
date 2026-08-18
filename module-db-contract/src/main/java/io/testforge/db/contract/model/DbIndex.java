package io.testforge.db.contract.model;

import java.util.List;

/**
 * Index of one table.
 *
 * <p>Indexes are matched across snapshots <em>by name</em>, so a renamed index
 * reports as removed plus added rather than as a rename. The index backing the
 * primary key is omitted from the model (see {@link DbTable}); index type,
 * partiality and cardinality are not modelled in v1.
 *
 * @param name    index name as reported by the database
 * @param columns indexed columns, in index order
 * @param unique  whether the index enforces uniqueness
 */
public record DbIndex(String name, List<String> columns, boolean unique) {

    public DbIndex {
        name = name == null ? "" : name;
        columns = columns == null ? List.of() : List.copyOf(columns);
    }

    /**
     * Human-readable one-line description used in diffs and reports.
     *
     * @return the description, e.g. {@code UNIQUE (email)}
     */
    public String describe() {
        return (unique ? "UNIQUE " : "") + "(" + String.join(", ", columns) + ")";
    }
}
