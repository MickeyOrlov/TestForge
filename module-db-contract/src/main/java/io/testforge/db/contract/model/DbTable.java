package io.testforge.db.contract.model;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * One table of the normalized TestForge database contract model.
 *
 * <p>Columns, foreign keys and indexes are sorted by name on construction so a
 * snapshot of an unchanged schema is byte-identical between runs. Physical
 * column order is deliberately <em>not</em> recorded: reordering columns does
 * not change what a consumer selecting by name sees.
 *
 * <p>Views, triggers, sequences, routines and partitions are out of the v1
 * model. The unique index that backs the primary key is dropped by the
 * inspector so that adding a primary key is reported once, not twice.
 *
 * @param name        table name exactly as the database reports it
 * @param columns     the table's columns, sorted by name
 * @param primaryKey  the primary key, or {@code null} when the table has none
 * @param foreignKeys the table's foreign keys, sorted by name
 * @param indexes     the table's indexes, sorted by name
 */
public record DbTable(
        String name,
        List<DbColumn> columns,
        DbPrimaryKey primaryKey,
        List<DbForeignKey> foreignKeys,
        List<DbIndex> indexes) {

    public DbTable {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Table name must not be null or blank");
        }
        columns = sorted(columns, DbColumn::name);
        foreignKeys = sorted(foreignKeys, DbForeignKey::name);
        indexes = sorted(indexes, DbIndex::name);
    }

    /**
     * Looks up a column by exact name.
     *
     * @param columnName the column name
     * @return the column, or empty when this table has no such column
     */
    public Optional<DbColumn> column(String columnName) {
        return columns.stream().filter(column -> column.name().equals(columnName)).findFirst();
    }

    private static <T> List<T> sorted(List<T> values, java.util.function.Function<T, String> key) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().sorted(Comparator.comparing(key)).toList();
    }
}
