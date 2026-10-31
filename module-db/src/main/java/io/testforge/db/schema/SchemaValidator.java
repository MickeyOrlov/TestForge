package io.testforge.db.schema;

import io.testforge.db.datasource.DataSourceRegistry;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrimaryKeyJoinColumn;
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
 * <p><b>Inheritance:</b> fields of {@code @MappedSuperclass} ancestors map onto the
 * entity's table, and a class-level {@code @AttributeOverride} renames them. Entity
 * hierarchies follow the root's {@code @Inheritance} strategy: {@code SINGLE_TABLE}
 * (the JPA default) validates every field of the chain against the root table plus
 * the discriminator column; {@code JOINED} validates the entity's own fields plus the
 * primary-key join column against its own table; {@code TABLE_PER_CLASS} validates
 * every field of the chain against the entity's own table.
 *
 * <p><b>Naming:</b> implicit names go through a {@link SchemaNamingStrategy}
 * ({@link SchemaNamingStrategy#SNAKE_CASE} by default, see {@link #withNaming});
 * explicit {@code @Column}/{@code @Table}/{@code @JoinColumn} names are taken as written.
 *
 * <p><b>Indexes and foreign keys:</b> {@link #indexDrift} checks every index the mapping
 * declares ({@code @Table(indexes)}, {@code @Table(uniqueConstraints)},
 * {@code @Column(unique = true)}) by its columns, never its name: a declared unique index
 * needs a unique database index on exactly that column set, a declared index needs a
 * database index starting with those columns in order. {@link #foreignKeyDrift} checks
 * foreign keys the mapping declares explicitly ({@code @JoinColumn(foreignKey = @ForeignKey(...))});
 * {@link #withImplicitForeignKeys} also expects one behind every {@code @ManyToOne}/{@code @OneToOne}
 * join column not marked {@code NO_CONSTRAINT}. Extra database indexes and keys are never
 * reported — the check is one-directional, like nullability.
 *
 * <p>Limitations (deliberate, to stay dependency-free and avoid false positives):
 * {@code @Embedded} fields are supported, including {@code @AttributeOverride} on the
 * embedding field; {@code @SecondaryTable} and {@code @AssociationOverride} are not.
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
    private final SchemaNamingStrategy naming;
    private final boolean implicitForeignKeys;

    record MappedColumn(
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
        this(dataSource, registry, SchemaNamingStrategy.SNAKE_CASE, false);
    }

    private SchemaValidator(DataSource dataSource, DataSourceRegistry registry, SchemaNamingStrategy naming,
                            boolean implicitForeignKeys) {
        this.dataSource = dataSource;
        this.registry = registry;
        this.naming = naming;
        this.implicitForeignKeys = implicitForeignKeys;
    }

    /**
     * Returns a new {@code SchemaValidator} that also expects a database foreign key behind
     * every {@code @ManyToOne}/{@code @OneToOne} join column, not only the ones declared with
     * {@code @ForeignKey}. Use it for services whose ORM creates its own constraints.
     */
    public SchemaValidator withImplicitForeignKeys(boolean enabled) {
        return new SchemaValidator(dataSource, registry, naming, enabled);
    }

    /**
     * Returns a new {@code SchemaValidator} that resolves implicit names with
     * {@code naming}, keeping this validator's datasource.
     */
    public SchemaValidator withNaming(SchemaNamingStrategy naming) {
        return new SchemaValidator(dataSource, registry, java.util.Objects.requireNonNull(naming, "naming"),
                implicitForeignKeys);
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
        return new SchemaValidator(registry.resolve(name), registry, naming, implicitForeignKeys);
    }

    /**
     * The table and columns an entity maps, resolved exactly as the drift
     * checks resolve them (naming strategy, overrides, embedded columns,
     * inheritance) — without touching a database. Lets other checks say which
     * of the project's entities a schema change reaches.
     *
     * @param entityClass the entity class to resolve
     * @return the entity's table and its mapped column names, in declaration order
     */
    public EntityMapping mapping(Class<?> entityClass) {
        List<String> columns = expectedColumns(entityClass).stream()
                .map(MappedColumn::name)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        return new EntityMapping(entityClass.getSimpleName(), tableName(entityClass), columns);
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
     * nullability drift, index drift and foreign key drift. Returns the concatenated list of findings
     * from {@link #missingColumns(Class)}, {@link #typeDrift(Class)}, {@link #nullabilityDrift(Class)},
     * {@link #indexDrift(Class)} and {@link #foreignKeyDrift(Class)}. When the table itself is absent
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
        combined.addAll(indexDrift(entityClass));
        combined.addAll(foreignKeyDrift(entityClass));
        return List.copyOf(combined);
    }

    /**
     * Indexes the mapping declares that the database does not have. Matched by columns, never
     * by name (generated names differ between databases). A declared unique index needs a unique
     * database index on exactly that column set; a declared index needs any database index whose
     * leading columns are the declared ones, in order.
     *
     * @param entityClass the entity class to inspect
     * @return index drift findings, or an empty list if in sync
     */
    public List<String> indexDrift(Class<?> entityClass) {
        String table = tableName(entityClass);
        List<DeclaredIndex> declared = declaredIndexes(entityClass);
        if (declared.isEmpty()) {
            return List.of();
        }
        List<ActualIndex> actual = actualIndexes(table);
        if (actual.isEmpty() && actualColumns(table).isEmpty()) {
            return List.of();
        }

        List<String> drift = new ArrayList<>();
        for (DeclaredIndex index : declared) {
            boolean present = index.unique()
                    ? actual.stream().anyMatch(a -> a.unique() && new java.util.HashSet<>(a.columns())
                            .equals(new java.util.HashSet<>(index.columns())))
                    : actual.stream().anyMatch(a -> a.columns().size() >= index.columns().size()
                            && a.columns().subList(0, index.columns().size()).equals(index.columns()));
            if (!present) {
                drift.add("%s: mapping declares %s on (%s) but the database has none"
                        .formatted(table, index.unique() ? "a unique index" : "an index",
                                String.join(", ", index.columns())));
            }
        }
        return List.copyOf(drift);
    }

    /**
     * Foreign keys the mapping declares that the database does not have, or that point at a
     * different table. By default only keys declared with {@code @ForeignKey} count; see
     * {@link #withImplicitForeignKeys}.
     *
     * @param entityClass the entity class to inspect
     * @return foreign key drift findings, or an empty list if in sync
     */
    public List<String> foreignKeyDrift(Class<?> entityClass) {
        String table = tableName(entityClass);
        List<DeclaredForeignKey> declared = declaredForeignKeys(entityClass);
        if (declared.isEmpty() || actualColumns(table).isEmpty()) {
            return List.of();
        }
        Map<String, List<ActualForeignKey>> actual = actualForeignKeys(table);

        List<String> drift = new ArrayList<>();
        for (DeclaredForeignKey key : declared) {
            List<ActualForeignKey> onColumn = actual.getOrDefault(key.column().toLowerCase(Locale.ROOT), List.of());
            if (onColumn.isEmpty()) {
                drift.add("%s.%s: mapping declares a foreign key to %s but the database has none"
                        .formatted(table, key.column(), key.targetTable()));
                continue;
            }
            boolean sameTarget = onColumn.stream().anyMatch(a -> a.targetTable().equalsIgnoreCase(key.targetTable())
                    && (key.targetColumn() == null || a.targetColumn().equalsIgnoreCase(key.targetColumn())));
            if (!sameTarget) {
                ActualForeignKey found = onColumn.getFirst();
                drift.add("%s.%s: foreign key points to %s.%s but the mapping expects %s%s"
                        .formatted(table, key.column(), found.targetTable().toLowerCase(Locale.ROOT),
                                found.targetColumn().toLowerCase(Locale.ROOT), key.targetTable(),
                                key.targetColumn() == null ? "" : "." + key.targetColumn()));
            }
        }
        return List.copyOf(drift);
    }

    record DeclaredIndex(List<String> columns, boolean unique) {}

    private record ActualIndex(List<String> columns, boolean unique) {}

    record DeclaredForeignKey(String column, String targetTable, String targetColumn) {}

    private record ActualForeignKey(String targetTable, String targetColumn) {}

    List<DeclaredIndex> declaredIndexes(Class<?> entityClass) {
        Hierarchy hierarchy = Hierarchy.of(entityClass);
        Class<?> tableOwner = hierarchy.strategy() == InheritanceType.SINGLE_TABLE ? hierarchy.root() : entityClass;
        List<DeclaredIndex> indexes = new ArrayList<>();

        Table table = tableOwner.getAnnotation(Table.class);
        if (table != null) {
            for (jakarta.persistence.Index index : table.indexes()) {
                List<String> columns = java.util.Arrays.stream(index.columnList().split(","))
                        .map(String::trim)
                        .filter(column -> !column.isEmpty())
                        // "created_at DESC" — the direction is not part of the column name
                        .map(column -> column.split("\\s+")[0].toLowerCase(Locale.ROOT))
                        .toList();
                if (!columns.isEmpty()) {
                    indexes.add(new DeclaredIndex(columns, index.unique()));
                }
            }
            for (jakarta.persistence.UniqueConstraint constraint : table.uniqueConstraints()) {
                List<String> columns = java.util.Arrays.stream(constraint.columnNames())
                        .map(column -> column.trim().toLowerCase(Locale.ROOT))
                        .toList();
                if (!columns.isEmpty()) {
                    indexes.add(new DeclaredIndex(columns, true));
                }
            }
        }

        for (Class<?> owner : hierarchy.fieldOwners(entityClass)) {
            for (Field field : owner.getDeclaredFields()) {
                Column column = field.getAnnotation(Column.class);
                JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
                boolean unique = (column != null && column.unique()) || (joinColumn != null && joinColumn.unique());
                if (unique) {
                    MappedColumn mapped = mappedColumn(field, null);
                    if (mapped != null && mapped.name() != null) {
                        indexes.add(new DeclaredIndex(List.of(mapped.name().toLowerCase(Locale.ROOT)), true));
                    }
                }
            }
        }
        return List.copyOf(indexes);
    }

    List<DeclaredForeignKey> declaredForeignKeys(Class<?> entityClass) {
        List<DeclaredForeignKey> keys = new ArrayList<>();
        for (Class<?> owner : Hierarchy.of(entityClass).fieldOwners(entityClass)) {
            for (Field field : owner.getDeclaredFields()) {
                if (!field.isAnnotationPresent(ManyToOne.class) && !field.isAnnotationPresent(OneToOne.class)) {
                    continue;
                }
                JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
                jakarta.persistence.ConstraintMode mode = joinColumn == null
                        ? jakarta.persistence.ConstraintMode.PROVIDER_DEFAULT
                        : joinColumn.foreignKey().value();
                boolean expected = mode == jakarta.persistence.ConstraintMode.CONSTRAINT
                        || (implicitForeignKeys && mode == jakarta.persistence.ConstraintMode.PROVIDER_DEFAULT);
                // a mappedBy side owns no column, and a non-entity target has no table to point at
                if (!expected || !field.getType().isAnnotationPresent(Entity.class) || mappedBy(field)) {
                    continue;
                }
                MappedColumn mapped = mappedColumn(field, null);
                if (mapped == null || mapped.name() == null) {
                    continue;
                }
                String targetColumn = joinColumn != null && !joinColumn.referencedColumnName().isBlank()
                        ? joinColumn.referencedColumnName()
                        : null;
                keys.add(new DeclaredForeignKey(mapped.name(), tableName(field.getType()), targetColumn));
            }
        }
        return List.copyOf(keys);
    }

    private static boolean mappedBy(Field field) {
        OneToOne oneToOne = field.getAnnotation(OneToOne.class);
        return oneToOne != null && !oneToOne.mappedBy().isBlank();
    }

    String tableName(Class<?> entityClass) {
        Hierarchy hierarchy = Hierarchy.of(entityClass);
        Class<?> tableOwner = hierarchy.strategy() == InheritanceType.SINGLE_TABLE ? hierarchy.root() : entityClass;
        Table table = tableOwner.getAnnotation(Table.class);
        if (table != null && !table.name().isBlank()) {
            return table.name();
        }
        Entity entity = tableOwner.getAnnotation(Entity.class);
        String logical = entity != null && !entity.name().isBlank() ? entity.name() : tableOwner.getSimpleName();
        return naming.physicalName(logical);
    }

    List<MappedColumn> expectedColumns(Class<?> entityClass) {
        Hierarchy hierarchy = Hierarchy.of(entityClass);
        Map<String, Column> classOverrides = classLevelOverrides(entityClass);
        List<MappedColumn> columns = new ArrayList<>();

        for (Class<?> owner : hierarchy.fieldOwners(entityClass)) {
            for (Field field : owner.getDeclaredFields()) {
                MappedColumn column = mappedColumn(field, owner == entityClass ? null : classOverrides.get(field.getName()));
                if (column != null) {
                    columns.addAll(column.name() == null ? embeddedColumns(field) : List.of(column));
                }
            }
        }

        if (hierarchy.strategy() == InheritanceType.JOINED && entityClass != hierarchy.root()) {
            columns.addAll(primaryKeyJoinColumns(entityClass, hierarchy));
        }
        if (hierarchy.strategy() == InheritanceType.SINGLE_TABLE && hierarchy.polymorphic()) {
            DiscriminatorColumn discriminator = hierarchy.root().getAnnotation(DiscriminatorColumn.class);
            String name = discriminator != null && !discriminator.name().isBlank() ? discriminator.name() : "DTYPE";
            columns.add(new MappedColumn(name, ColumnTypeFamily.UNKNOWN, false, true, "discriminator"));
        }
        return columns;
    }

    /**
     * One field's column, or {@code null} for fields that map to nothing on this
     * table. An {@code @Embedded} field returns a marker with a {@code null} name;
     * the caller expands it.
     */
    private MappedColumn mappedColumn(Field field, Column inheritedOverride) {
        if (Modifier.isStatic(field.getModifiers())
                || field.isSynthetic()
                || field.isAnnotationPresent(Transient.class)
                || field.isAnnotationPresent(OneToMany.class)
                || field.isAnnotationPresent(ManyToMany.class)) {
            return null;
        }

        if (field.isAnnotationPresent(Embedded.class)) {
            return new MappedColumn(null, ColumnTypeFamily.UNKNOWN, false, false, "embedded");
        }

        JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
        if (joinColumn != null) {
            String columnName = joinColumn.name().isBlank()
                    ? naming.physicalName(field.getName()) + "_id"
                    : joinColumn.name();
            return new MappedColumn(columnName, ColumnTypeFamily.UNKNOWN, isDeclaredNotNull(field, null), true,
                    field.getType().getSimpleName());
        }

        if (field.isAnnotationPresent(ManyToOne.class) || field.isAnnotationPresent(OneToOne.class)) {
            return new MappedColumn(naming.physicalName(field.getName()) + "_id", ColumnTypeFamily.UNKNOWN,
                    isDeclaredNotNull(field, null), true, field.getType().getSimpleName());
        }

        Column column = inheritedOverride != null ? inheritedOverride : field.getAnnotation(Column.class);
        String columnName = column != null && !column.name().isBlank()
                ? column.name()
                : naming.physicalName(field.getName());
        return new MappedColumn(columnName, ColumnTypeFamily.ofJavaType(field), isDeclaredNotNull(field, inheritedOverride),
                false, field.getType().getSimpleName());
    }

    /** {@code @AttributeOverride} on the entity class renames columns it inherits from a mapped superclass. */
    private static Map<String, Column> classLevelOverrides(Class<?> entityClass) {
        Map<String, Column> overrides = new HashMap<>();
        for (AttributeOverride override : entityClass.getAnnotationsByType(AttributeOverride.class)) {
            overrides.put(override.name(), override.column());
        }
        return overrides;
    }

    /** A JOINED subclass table carries the root's key as its own primary key. */
    private List<MappedColumn> primaryKeyJoinColumns(Class<?> entityClass, Hierarchy hierarchy) {
        PrimaryKeyJoinColumn[] declared = entityClass.getAnnotationsByType(PrimaryKeyJoinColumn.class);
        List<MappedColumn> columns = new ArrayList<>();
        if (declared.length > 0) {
            for (PrimaryKeyJoinColumn join : declared) {
                if (!join.name().isBlank()) {
                    columns.add(new MappedColumn(join.name(), ColumnTypeFamily.UNKNOWN, true, true, "primary key join"));
                }
            }
            if (!columns.isEmpty()) {
                return columns;
            }
        }
        for (Class<?> owner : hierarchy.fieldOwners(hierarchy.root())) {
            for (Field field : owner.getDeclaredFields()) {
                if (field.isAnnotationPresent(Id.class)) {
                    MappedColumn id = mappedColumn(field, null);
                    if (id != null && id.name() != null) {
                        columns.add(new MappedColumn(id.name(), ColumnTypeFamily.UNKNOWN, true, true, "primary key join"));
                    }
                }
            }
        }
        return columns;
    }

    /**
     * The entity's position in its JPA hierarchy: the root entity, the root's
     * strategy, and which classes contribute fields to a given entity's table.
     */
    private record Hierarchy(Class<?> root, InheritanceType strategy, boolean polymorphic) {

        static Hierarchy of(Class<?> entityClass) {
            Class<?> root = entityClass;
            boolean hasEntityAncestor = false;
            for (Class<?> current = entityClass.getSuperclass(); current != null && current != Object.class;
                    current = current.getSuperclass()) {
                if (current.isAnnotationPresent(Entity.class)) {
                    root = current;
                    hasEntityAncestor = true;
                }
            }
            Inheritance inheritance = root.getAnnotation(Inheritance.class);
            InheritanceType strategy = inheritance != null ? inheritance.strategy() : InheritanceType.SINGLE_TABLE;
            boolean polymorphic = hasEntityAncestor
                    || inheritance != null
                    || root.isAnnotationPresent(DiscriminatorColumn.class);
            return new Hierarchy(root, strategy, polymorphic);
        }

        /**
         * Classes whose declared fields land in {@code entityClass}'s table, most
         * general first. Mapped superclasses always contribute; entity ancestors
         * only outside JOINED, where each entity keeps its own table.
         */
        List<Class<?>> fieldOwners(Class<?> entityClass) {
            List<Class<?>> owners = new ArrayList<>();
            boolean crossedEntity = false;
            for (Class<?> current = entityClass; current != null && current != Object.class;
                    current = current.getSuperclass()) {
                boolean isEntity = current.isAnnotationPresent(Entity.class);
                boolean isMappedSuperclass = current.isAnnotationPresent(MappedSuperclass.class);
                if (current == entityClass) {
                    owners.add(current);
                    continue;
                }
                if (strategy == InheritanceType.JOINED && (isEntity || crossedEntity)) {
                    // the ancestor entity (and everything above it) lives in its own table
                    crossedEntity = true;
                    continue;
                }
                if (isEntity || isMappedSuperclass) {
                    owners.add(current);
                }
            }
            java.util.Collections.reverse(owners);
            return owners;
        }
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
                        : naming.physicalName(field.getName());
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
        // @AttributeOverride REPLACES the embeddable's column mapping, so when
        // the embedding entity overrides a column, only the override speaks.
        // Or-ing it with the embeddable's own @Column would report a healthy
        // overridden-to-nullable schema as "declares NOT NULL" — a false
        // positive, which is exactly what this feature exists to avoid.
        if (overrideColumn != null) {
            return !overrideColumn.nullable();
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

    private List<ActualIndex> actualIndexes(String table) {
        try (Connection connection = resolveDataSource().getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            for (String candidate : List.of(table, table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT))) {
                // name -> (position -> column), keeping each index's column order
                Map<String, java.util.TreeMap<Short, String>> columns = new java.util.LinkedHashMap<>();
                Map<String, Boolean> unique = new HashMap<>();
                try (ResultSet rs = metaData.getIndexInfo(null, null, candidate, false, true)) {
                    while (rs.next()) {
                        String name = rs.getString("INDEX_NAME");
                        String column = rs.getString("COLUMN_NAME");
                        if (name == null || column == null) {
                            continue;
                        }
                        columns.computeIfAbsent(name, ignored -> new java.util.TreeMap<>())
                                .put(rs.getShort("ORDINAL_POSITION"), column.toLowerCase(Locale.ROOT));
                        unique.put(name, !rs.getBoolean("NON_UNIQUE"));
                    }
                }
                if (!columns.isEmpty()) {
                    List<ActualIndex> indexes = new ArrayList<>();
                    columns.forEach((name, byPosition) ->
                            indexes.add(new ActualIndex(List.copyOf(byPosition.values()), unique.get(name))));
                    return indexes;
                }
            }
            return List.of();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read indexes of table '%s'".formatted(table), e);
        }
    }

    private Map<String, List<ActualForeignKey>> actualForeignKeys(String table) {
        try (Connection connection = resolveDataSource().getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            for (String candidate : List.of(table, table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT))) {
                Map<String, List<ActualForeignKey>> keys = new HashMap<>();
                try (ResultSet rs = metaData.getImportedKeys(null, null, candidate)) {
                    while (rs.next()) {
                        String column = rs.getString("FKCOLUMN_NAME");
                        if (column == null) {
                            continue;
                        }
                        keys.computeIfAbsent(column.toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                                .add(new ActualForeignKey(rs.getString("PKTABLE_NAME"), rs.getString("PKCOLUMN_NAME")));
                    }
                }
                if (!keys.isEmpty()) {
                    return keys;
                }
            }
            return Map.of();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read foreign keys of table '%s'".formatted(table), e);
        }
    }

    static String camelToSnake(String value) {
        return SchemaNamingStrategy.SNAKE_CASE.physicalName(value);
    }
}
