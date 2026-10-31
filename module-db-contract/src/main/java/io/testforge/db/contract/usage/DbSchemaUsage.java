package io.testforge.db.contract.usage;

import io.testforge.db.contract.diff.DbChange;
import io.testforge.db.contract.diff.DbChangeType;
import io.testforge.db.schema.EntityMapping;
import io.testforge.db.schema.SchemaValidator;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * Who in this project a schema change reaches. A verdict says what a change
 * does to <em>a</em> consumer; this says whether one of <em>ours</em> — an
 * entity the tests read and write through — is in its way.
 *
 * <p>A change to a column reaches the entities mapping that column; any other
 * change (a table, a new column, a key or an index) reaches the entities
 * mapping the table, since inserts and joins go through it. Matching ignores
 * case, because databases report identifiers in their own.
 */
@FunctionalInterface
public interface DbSchemaUsage {

    /** No usage information: every change reports no users. */
    DbSchemaUsage NONE = change -> List.of();

    /**
     * @param change one structural change
     * @return the names of the entities it reaches, sorted; empty when none
     */
    List<String> usersOf(DbChange change);

    /** Usage from resolved mappings. */
    static DbSchemaUsage ofMappings(Collection<EntityMapping> mappings) {
        List<EntityMapping> known = List.copyOf(mappings);
        return change -> {
            boolean columnLevel = change.object() != null && change.type().name().startsWith("COLUMN_")
                    && change.type() != DbChangeType.COLUMN_ADDED;
            TreeSet<String> users = new TreeSet<>();
            for (EntityMapping mapping : known) {
                boolean reached = columnLevel
                        ? mapping.mapsColumn(change.table(), change.object())
                        : mapping.mapsTable(change.table());
                if (reached) {
                    users.add(mapping.entity());
                }
            }
            return List.copyOf(users);
        };
    }

    /** Usage from entity classes, resolved the way {@code SchemaValidator} resolves them. */
    static DbSchemaUsage ofEntities(SchemaValidator validator, Collection<Class<?>> entities) {
        return ofMappings(entities.stream().map(validator::mapping).toList());
    }
}
