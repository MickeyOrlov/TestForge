package io.testforge.db.contract.model;

import java.util.List;

/**
 * Index of one table.
 *
 * <p>Indexes are matched across snapshots <em>by name</em>, so a renamed index
 * reports as removed plus added rather than as a rename. The index backing the
 * primary key is omitted from the model (see {@link DbTable}); index type and
 * cardinality are not modelled.
 *
 * @param name      index name as reported by the database
 * @param columns   indexed columns, in index order
 * @param unique    whether the index enforces uniqueness
 * @param predicate the partial index's {@code WHERE} condition as the driver
 *                  reports it, or empty for a full index. It decides which rows
 *                  {@code unique} even applies to, so an index that keeps its
 *                  name, columns and uniqueness can still change what it
 *                  guarantees purely by gaining or losing this
 */
public record DbIndex(String name, List<String> columns, boolean unique, String predicate) {

    public DbIndex {
        name = name == null ? "" : name;
        columns = columns == null ? List.of() : List.copyOf(columns);
        predicate = predicate == null ? "" : predicate.trim();
    }

    /**
     * Whether this index covers only some rows.
     *
     * @return {@code true} when a {@code WHERE} predicate is recorded
     */
    public boolean partial() {
        return !predicate.isBlank();
    }

    /**
     * Human-readable one-line description used in diffs and reports.
     *
     * @return the description, e.g. {@code UNIQUE (email)}
     */
    public String describe() {
        String description = (unique ? "UNIQUE " : "") + "(" + String.join(", ", columns) + ")";
        return partial() ? description + " WHERE " + predicate : description;
    }
}
