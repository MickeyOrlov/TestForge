package io.testforge.db.schema;

import java.util.List;
import java.util.Locale;

/**
 * Where one JPA entity lives in the schema, as {@link SchemaValidator#mapping(Class)}
 * resolves it.
 *
 * @param entity  the entity's simple class name
 * @param table   the table it maps
 * @param columns the column names it maps, in declaration order
 */
public record EntityMapping(String entity, String table, List<String> columns) {

    public EntityMapping {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }

    /** Whether the entity maps {@code table}; databases report identifiers in their own case, so case is ignored. */
    public boolean mapsTable(String name) {
        return name != null && table.toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT));
    }

    /** Whether the entity maps {@code column} of {@code table}, ignoring case. */
    public boolean mapsColumn(String tableName, String column) {
        return mapsTable(tableName) && column != null
                && columns.stream().anyMatch(mapped -> mapped.equalsIgnoreCase(column));
    }
}
