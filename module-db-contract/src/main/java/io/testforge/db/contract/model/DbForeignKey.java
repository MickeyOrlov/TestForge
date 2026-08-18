package io.testforge.db.contract.model;

import java.util.List;

/**
 * Foreign key of one table.
 *
 * <p>Foreign keys are matched across snapshots <em>by name</em>, so a renamed
 * constraint reports as removed plus added rather than as a rename. Referential
 * actions ({@code ON DELETE}/{@code ON UPDATE}) and deferrability are not
 * modelled in v1.
 *
 * @param name              constraint name as reported by the database
 * @param columns           the referencing columns, in key order
 * @param referencedTable   name of the referenced table, qualified as
 *                          {@code schema.table} when the key points outside the
 *                          inspected schema and bare when it does not
 * @param referencedColumns the referenced columns, in key order
 */
public record DbForeignKey(
        String name,
        List<String> columns,
        String referencedTable,
        List<String> referencedColumns) {

    public DbForeignKey {
        name = name == null ? "" : name;
        columns = columns == null ? List.of() : List.copyOf(columns);
        referencedTable = referencedTable == null ? "" : referencedTable;
        referencedColumns = referencedColumns == null ? List.of() : List.copyOf(referencedColumns);
    }

    /**
     * Human-readable one-line description used in diffs and reports.
     *
     * @return the description, e.g. {@code (customer_id) -> customers(id)}
     */
    public String describe() {
        return "(" + String.join(", ", columns) + ") -> "
                + referencedTable + "(" + String.join(", ", referencedColumns) + ")";
    }
}
