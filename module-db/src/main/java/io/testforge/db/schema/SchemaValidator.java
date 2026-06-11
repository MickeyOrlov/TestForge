package io.testforge.db.schema;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Detects drift between test-framework entity mappings and the real database
 * schema. Test frameworks that map service tables directly rot silently when
 * services migrate their schemas; running this validator per entity (e.g. in a
 * scheduled CI job) turns that silent rot into a readable diff.
 *
 * <p>Limitations (deliberate, to stay dependency-free): only field-level
 * mappings are inspected; inherited fields and custom naming strategies other
 * than camelCase&rarr;snake_case are not resolved. {@code @Embedded} fields
 * are supported, including {@code @AttributeOverride} on the embedding field.
 */
public class SchemaValidator {

    private final DataSource dataSource;

    public SchemaValidator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Returns a list of problems for the given entity: mapped columns that do
     * not exist in the database. Empty list means the mapping is in sync.
     */
    public List<String> missingColumns(Class<?> entityClass) {
        String table = tableName(entityClass);
        Set<String> actual = actualColumns(table);

        if (actual.isEmpty()) {
            return List.of("table '%s' not found in database".formatted(table));
        }

        return expectedColumns(entityClass).stream()
                .filter(column -> !actual.contains(column.toLowerCase(Locale.ROOT)))
                .map(column -> "%s.%s".formatted(table, column))
                .toList();
    }

    String tableName(Class<?> entityClass) {
        Table table = entityClass.getAnnotation(Table.class);
        if (table != null && !table.name().isBlank()) {
            return table.name();
        }
        return camelToSnake(entityClass.getSimpleName());
    }

    List<String> expectedColumns(Class<?> entityClass) {
        List<String> columns = new ArrayList<>();

        for (Field field : entityClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    || field.isAnnotationPresent(Transient.class)
                    || field.isAnnotationPresent(OneToMany.class)
                    || field.isAnnotationPresent(ManyToMany.class)) {
                continue;
            }

            if (field.isAnnotationPresent(Embedded.class)) {
                columns.addAll(embeddedColumns(field));
                continue;
            }

            JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
            if (joinColumn != null) {
                columns.add(joinColumn.name().isBlank()
                        ? camelToSnake(field.getName()) + "_id"
                        : joinColumn.name());
                continue;
            }

            if (field.isAnnotationPresent(ManyToOne.class) || field.isAnnotationPresent(OneToOne.class)) {
                columns.add(camelToSnake(field.getName()) + "_id");
                continue;
            }

            Column column = field.getAnnotation(Column.class);
            columns.add(column != null && !column.name().isBlank()
                    ? column.name()
                    : camelToSnake(field.getName()));
        }

        return columns;
    }

    /**
     * Embeddable fields map onto the owning table; {@code @AttributeOverride}
     * on the embedded field wins over the embeddable's own column names.
     */
    private List<String> embeddedColumns(Field embeddedField) {
        Map<String, String> overrides = new HashMap<>();
        for (AttributeOverride override : embeddedField.getAnnotationsByType(AttributeOverride.class)) {
            overrides.put(override.name(), override.column().name());
        }

        List<String> columns = new ArrayList<>();
        for (Field field : embeddedField.getType().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isAnnotationPresent(Transient.class)) {
                continue;
            }

            String overridden = overrides.get(field.getName());
            if (overridden != null && !overridden.isBlank()) {
                columns.add(overridden);
                continue;
            }

            Column column = field.getAnnotation(Column.class);
            columns.add(column != null && !column.name().isBlank()
                    ? column.name()
                    : camelToSnake(field.getName()));
        }
        return columns;
    }

    private Set<String> actualColumns(String table) {
        Set<String> columns = new HashSet<>();

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();

            // identifier case differs per vendor: H2 stores upper, Postgres lower
            for (String candidate : List.of(table,
                    table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT))) {
                try (ResultSet resultSet = metaData.getColumns(null, null, candidate, null)) {
                    while (resultSet.next()) {
                        columns.add(resultSet.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                    }
                }
                if (!columns.isEmpty()) {
                    break;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read columns of table '%s'".formatted(table), e);
        }

        return columns;
    }

    static String camelToSnake(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
