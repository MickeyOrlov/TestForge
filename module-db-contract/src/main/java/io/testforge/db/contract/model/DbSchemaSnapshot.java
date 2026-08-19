package io.testforge.db.contract.model;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * A deterministic, Git-friendly snapshot of one database schema.
 *
 * <p>The snapshot holds structure only. It carries no timestamp, no database
 * product name and no connection detail on purpose: re-capturing an unchanged
 * schema must produce a byte-identical file, so that a committed baseline
 * changes in git exactly when the contract changes.
 *
 * <p>Identifiers are stored exactly as the database reports them (PostgreSQL
 * folds to lower case, H2 to upper case). Snapshots are therefore comparable
 * within one database lineage, not across vendors — cross-vendor comparison is
 * out of the v1 scope.
 *
 * @param formatVersion snapshot format version, for future migrations
 * @param schema        the schema name the snapshot was taken from
 * @param tables        the schema's tables, sorted by name
 */
public record DbSchemaSnapshot(int formatVersion, String schema, List<DbTable> tables) {

    /**
     * Current snapshot format version.
     *
     * <p>2 added the partial-index predicate and foreign-key referential
     * actions. A version 1 baseline lacks both, so comparing it against a
     * version 2 capture would report every partial index and every key as
     * changed — which is why {@link io.testforge.db.contract.snapshot.DbSchemaSnapshotStore}
     * refuses to read a snapshot of any other version instead of guessing.
     */
    public static final int FORMAT_VERSION = 2;

    public DbSchemaSnapshot {
        if (formatVersion <= 0) {
            formatVersion = FORMAT_VERSION;
        }
        schema = schema == null ? "" : schema;
        tables = tables == null
                ? List.of()
                : tables.stream().sorted(Comparator.comparing(DbTable::name)).toList();
    }

    /**
     * Creates a snapshot in the current format version.
     *
     * @param schema the schema name
     * @param tables the schema's tables, in any order
     * @return the snapshot
     */
    public static DbSchemaSnapshot of(String schema, List<DbTable> tables) {
        return new DbSchemaSnapshot(FORMAT_VERSION, schema, tables);
    }

    /**
     * Looks up a table by exact name.
     *
     * @param tableName the table name
     * @return the table, or empty when this snapshot has no such table
     */
    public Optional<DbTable> table(String tableName) {
        return tables.stream().filter(table -> table.name().equals(tableName)).findFirst();
    }
}
