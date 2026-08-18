package io.testforge.db.contract.diff;

/**
 * One structural difference between a baseline and a current schema snapshot.
 *
 * <p>A change is a pure fact: it carries no judgement about whether it breaks
 * anything. Classification is the separate job of a
 * {@link io.testforge.db.contract.policy.DbCompatibilityPolicy}.
 *
 * @param type   what changed
 * @param table  the table the change belongs to
 * @param object the column, index or constraint name, or {@code null} for
 *               table-level changes
 * @param before the baseline description, or {@code null} when the object did
 *               not exist in the baseline
 * @param after  the current description, or {@code null} when the object no
 *               longer exists
 */
public record DbChange(DbChangeType type, String table, String object, String before, String after) {

    public DbChange {
        if (type == null) {
            throw new IllegalArgumentException("Change type must not be null");
        }
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("Change table must not be null or blank");
        }
    }

    /**
     * Creates a table-level change.
     *
     * @param type   what changed
     * @param table  the table name
     * @param before the baseline description, or {@code null}
     * @param after  the current description, or {@code null}
     * @return the change
     */
    public static DbChange ofTable(DbChangeType type, String table, String before, String after) {
        return new DbChange(type, table, null, before, after);
    }

    /**
     * Dotted path of the changed object, used as the report's stable identifier.
     *
     * @return {@code table} for table-level changes, {@code table.object} otherwise
     */
    public String path() {
        return object == null || object.isBlank() ? table : table + "." + object;
    }
}
