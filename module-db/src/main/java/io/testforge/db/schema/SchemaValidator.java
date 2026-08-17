package io.testforge.db.schema;

import io.testforge.db.datasource.DataSourceRegistry;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Id;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Detects drift between test-framework entity mappings and the real database
 * schema. Test frameworks that map service tables directly rot silently when
 * services migrate their schemas; running this validator per entity (e.g. in a
 * scheduled CI job) turns that silent rot into a readable diff.
 *
 * <p>Limitations (deliberate, to stay dependency-free and avoid false positives):
 * only field-level mappings are inspected; inherited fields and custom naming
 * strategies other than camelCase&rarr;snake_case are not resolved. {@code @Embedded}
 * fields are supported, including {@code @AttributeOverride} on the embedding field.
 * Type comparison is performed by high-level type families (see {@link ColumnTypeFamily});
 * column length and precision are not compared. Nullability validation detects only
 * columns where the mapping claims NOT NULL but the database allows NULL. Unresolvable
 * field types and {@link java.util.UUID} produce no type drift findings (the silence rule).
 * Relationship columns ({@code @ManyToOne}, {@code @OneToOne}, {@code @JoinColumn})
 * are skipped for type drift checks.
 *
 * <p><b>Named datasource example:</b>
 * <pre>{@code
 * // Auto-configured bean resolves the default datasource:
 * schemaValidator.missingColumns(OrderEntity.class);
 *
 * // Validate against a specific named datasource:
 * schemaValidator.forDataSource("auditDataSource")
 *                .missingColumns(AuditEntry.class);
 * }</pre>
 */
public class SchemaValidator {

    private final DataSource dataSource;
    private final DataSourceRegistry registry;

    private record MappedColumn(
            String name,
            ColumnTypeFamily family,
            boolean declaredNotNull,
            boolean isRelationship,
            String javaTypeName
    ) {}

    private record ActualColumn(
            String name,
            int jdbcType,
            String typeName,
            boolean nullable
    ) {}

    public SchemaValidator(DataSource dataSource) {
        this(dataSource, null);
    }

    /**
     * Creates a registry-backed validator. The default datasource is resolved
     * on each call to {@link #missingColumns(Class)} rather than being captured
     * in the constructor.
     *
     * <p>Note that the auto-configured {@link DataSourceRegistry} holds
     * already-instantiated {@code DataSource} beans, so a {@code @Lazy}
     * datasource is still created when the application context starts.
     */
    public SchemaValidator(DataSourceRegistry registry) {
        this(null, registry);
    }

    private SchemaValidator(DataSource dataSource, DataSourceRegistry registry) {
        this.dataSource = dataSource;
        this.registry = registry;
    }

    /**
     * Returns a new {@code SchemaValidator} bound to the named datasource.
     * A {@code null}, empty, or blank name binds the default datasource.
     *
     * @throws IllegalArgumentException if the name does not match any
     *     configured datasource (the message lists the known names)
     * @throws IllegalStateException if this validator was built with the
     *     legacy {@link #SchemaValidator(DataSource)} constructor and has
     *     no {@link DataSourceRegistry}
     */
    public SchemaValidator forDataSource(String name) {
        if (registry == null) {
            throw new IllegalStateException(
                    "No DataSourceRegistry is available on this SchemaValidator instance. "
                            + "Inject the auto-configured SchemaValidator bean instead of "
                            + "constructing one with SchemaValidator(DataSource).");
        }
        // Keep the registry so the returned view can itself be re-targeted.
        return new SchemaValidator(registry.resolve(name), registry);
    }

    /**
     * Returns a list of problems for the given entity: mapped columns that do
     * not exist in the database. Empty list means the mapping is in sync.
     */
    public List<String> missingColumns(Class<?> entityClass) {
        String table = tableName(entityClass);
        Map<String, ActualColumn> actual = actualColumns(table);

        if (actual.isEmpty()) {
            return List.of("table '%s' not found in database".formatted(table));
        }

        return expectedColumns(entityClass).stream()
                .filter(column -> !actual.containsKey(column.name().toLowerCase(Locale.ROOT)))
                .map(column -> "%s.%s".formatted(table, column.name()))
                .toList();
    }

    /**
     * Detects type family drift between mapped entity fields and existing database columns.
     * Reports only when both sides resolve to known type families (see {@link ColumnTypeFamily})
     * and differ. Unresolvable types, {@link java.util.UUID}, and relationship columns are skipped.
     * Length and precision differences are ignored.
     *
     * @param entityClass the entity class to inspect
     * @return a list of type drift findings, or an empty list if in sync
     */
    public List<String> typeDrift(Class<?> entityClass) {
        String table = tableName(entityClass);
        Map<String, ActualColumn> actualMap = actualColumns(table);

        if (actualMap.isEmpty()) {
            return List.of();
        }

        List<String> drift = new ArrayList<>();
        for (MappedColumn mapped : expectedColumns(entityClass)) {
            if (mapped.isRelationship()) {
                continue;
            }
            if (mapped.family() == ColumnTypeFamily.UNKNOWN) {
                continue;
            }

            ActualColumn actual = actualMap.get(mapped.name().toLowerCase(Locale.ROOT));
            if (actual == null) {
                continue;
            }

            ColumnTypeFamily dbFamily = ColumnTypeFamily.ofJdbcType(actual.jdbcType());
            if (dbFamily == ColumnTypeFamily.UNKNOWN) {
                continue;
            }

            if (mapped.family() != dbFamily) {
                String typeName = actual.typeName() != null ? actual.typeName() : String.valueOf(actual.jdbcType());
                drift.add("%s.%s: mapped as %s (%s) but database column is %s (%s)"
                        .formatted(table, mapped.name(), mapped.family().name(), mapped.javaTypeName(),
                                dbFamily.name(), typeName));
            }
        }
        return List.copyOf(drift);
    }

    /**
     * Detects nullability drift between mapped entity fields and existing database columns.
     * Reports only when the mapping explicitly claims NOT NULL (via {@code @Column(nullable=false)},
     * {@code @JoinColumn(nullable=false)}, {@code @Basic(optional=false)}, or {@code @Id}) but the
     * database column allows NULL. Silent mappings against database NOT NULL columns produce no findings.
     *
     * @param entityClass the entity class to inspect
     * @return a list of nullability drift findings, or an empty list if in sync
     */
    public List<String> nullabilityDrift(Class<?> entityClass) {
        String table = tableName(entityClass);
        Map<String, ActualColumn> actualMap = actualColumns(table);

        if (actualMap.isEmpty()) {
            return List.of();
        }

        List<String> drift = new ArrayList<>();
        for (MappedColumn mapped : expectedColumns(entityClass)) {
            if (!mapped.declaredNotNull()) {
                continue;
            }

            ActualColumn actual = actualMap.get(mapped.name().toLowerCase(Locale.ROOT));
            if (actual == null) {
                continue;
            }

            if (actual.nullable()) {
                drift.add("%s.%s: mapping declares NOT NULL but database column is nullable"
                        .formatted(table, mapped.name()));
            }
        }
        return List.copyOf(drift);
    }

    /**
     * Detects all forms of schema drift for the given entity: missing columns, type family drift,
     * and nullability drift. Returns the concatenated list of findings from {@link #missingColumns(Class)},
     * {@link #typeDrift(Class)}, and {@link #nullabilityDrift(Class)}. When the table itself is absent
     * from the database, returns only the table-not-found message.
     *
     * @param entityClass the entity class to inspect
     * @return a concatenated list of schema drift findings, or an empty list if in sync
     */
    public List<String> schemaDrift(Class<?> entityClass) {
        List<String> missing = missingColumns(entityClass);
        if (missing.size() == 1 && missing.get(0).contains("not found in database")) {
            return missing;
        }

        List<String> combined = new ArrayList<>(missing);
        combined.addAll(typeDrift(entityClass));
        combined.addAll(nullabilityDrift(entityClass));
        return List.copyOf(combined);
    }

    String tableName(Class<?> entityClass) {
        Table table = entityClass.getAnnotation(Table.class);
        if (table != null && !table.name().isBlank()) {
            return table.name();
        }
        return camelToSnake(entityClass.getSimpleName());
    }

    List<MappedColumn> expectedColumns(Class<?> entityClass) {
        List<MappedColumn> columns = new ArrayList<>();

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
                String columnName = joinColumn.name().isBlank()
                        ? camelToSnake(field.getName()) + "_id"
                        : joinColumn.name();
                columns.add(new MappedColumn(
                        columnName,
                        ColumnTypeFamily.UNKNOWN,
                        isDeclaredNotNull(field, null),
                        true,
                        field.getType().getSimpleName()));
                continue;
            }

            if (field.isAnnotationPresent(ManyToOne.class) || field.isAnnotationPresent(OneToOne.class)) {
                String columnName = camelToSnake(field.getName()) + "_id";
                columns.add(new MappedColumn(
                        columnName,
                        ColumnTypeFamily.UNKNOWN,
                        isDeclaredNotNull(field, null),
                        true,
                        field.getType().getSimpleName()));
                continue;
            }

            Column column = field.getAnnotation(Column.class);
            String columnName = column != null && !column.name().isBlank()
                    ? column.name()
                    : camelToSnake(field.getName());
            columns.add(new MappedColumn(
                    columnName,
                    ColumnTypeFamily.ofJavaType(field),
                    isDeclaredNotNull(field, null),
                    false,
                    field.getType().getSimpleName()));
        }

        return columns;
    }

    /**
     * Embeddable fields map onto the owning table; {@code @AttributeOverride}
     * on the embedded field wins over the embeddable's own column names.
     */
    private List<MappedColumn> embeddedColumns(Field embeddedField) {
        Map<String, Column> overrides = new HashMap<>();
        for (AttributeOverride override : embeddedField.getAnnotationsByType(AttributeOverride.class)) {
            overrides.put(override.name(), override.column());
        }

        List<MappedColumn> columns = new ArrayList<>();
        for (Field field : embeddedField.getType().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isAnnotationPresent(Transient.class)) {
                continue;
            }

            Column overrideColumn = overrides.get(field.getName());
            String columnName;
            if (overrideColumn != null && !overrideColumn.name().isBlank()) {
                columnName = overrideColumn.name();
            } else {
                Column column = field.getAnnotation(Column.class);
                columnName = column != null && !column.name().isBlank()
                        ? column.name()
                        : camelToSnake(field.getName());
            }

            boolean isRelationship = field.isAnnotationPresent(JoinColumn.class)
                    || field.isAnnotationPresent(ManyToOne.class)
                    || field.isAnnotationPresent(OneToOne.class);

            ColumnTypeFamily family = isRelationship
                    ? ColumnTypeFamily.UNKNOWN
                    : ColumnTypeFamily.ofJavaType(field);

            columns.add(new MappedColumn(
                    columnName,
                    family,
                    isDeclaredNotNull(field, overrideColumn),
                    isRelationship,
                    field.getType().getSimpleName()));
        }
        return columns;
    }

    private boolean isDeclaredNotNull(Field field, Column overrideColumn) {
        if (field.isAnnotationPresent(Id.class)) {
            return true;
        }
        Column column = field.getAnnotation(Column.class);
        if (column != null && !column.nullable()) {
            return true;
        }
        JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
        if (joinColumn != null && !joinColumn.nullable()) {
            return true;
        }
        Basic basic = field.getAnnotation(Basic.class);
        if (basic != null && !basic.optional()) {
            return true;
        }
        if (overrideColumn != null && !overrideColumn.nullable()) {
            return true;
        }
        return false;
    }

    private DataSource resolveDataSource() {
        if (dataSource != null) {
            return dataSource;
        }
        return registry.resolveDefault();
    }

    private Map<String, ActualColumn> actualColumns(String table) {
        Map<String, ActualColumn> columns = new HashMap<>();

        try (Connection connection = resolveDataSource().getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();

            // identifier case differs per vendor: H2 stores upper, Postgres lower
            for (String candidate : List.of(table,
                    table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT))) {
                try (ResultSet resultSet = metaData.getColumns(null, null, candidate, null)) {
                    while (resultSet.next()) {
                        String columnName = resultSet.getString("COLUMN_NAME");
                        if (columnName != null) {
                            int dataType = resultSet.getInt("DATA_TYPE");
                            String typeName = resultSet.getString("TYPE_NAME");
                            String isNullable = resultSet.getString("IS_NULLABLE");
                            boolean nullable = "YES".equalsIgnoreCase(isNullable);
                            columns.put(columnName.toLowerCase(Locale.ROOT),
                                    new ActualColumn(columnName, dataType, typeName, nullable));
                        }
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
