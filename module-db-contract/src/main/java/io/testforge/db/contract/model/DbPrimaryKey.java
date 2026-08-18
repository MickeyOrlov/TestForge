package io.testforge.db.contract.model;

import java.util.List;

/**
 * Primary key of one table.
 *
 * <p>The constraint {@code name} is recorded for readability only. Comparison
 * uses the column list alone, so renaming a primary-key constraint is not a
 * contract change even though it does show up in the snapshot's git diff.
 *
 * @param name    constraint name as reported by the database
 * @param columns key columns, in key order
 */
public record DbPrimaryKey(String name, List<String> columns) {

    public DbPrimaryKey {
        name = name == null ? "" : name;
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
