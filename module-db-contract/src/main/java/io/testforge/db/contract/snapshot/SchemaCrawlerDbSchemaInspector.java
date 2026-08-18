package io.testforge.db.contract.snapshot;

import io.testforge.db.contract.model.DbColumn;
import io.testforge.db.contract.model.DbForeignKey;
import io.testforge.db.contract.model.DbIndex;
import io.testforge.db.contract.model.DbPrimaryKey;
import io.testforge.db.contract.model.DbReferentialAction;
import io.testforge.db.contract.model.DbSchemaSnapshot;
import io.testforge.db.contract.model.DbTable;
import io.testforge.db.schema.ColumnTypeFamily;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import schemacrawler.inclusionrule.RegularExpressionInclusionRule;
import schemacrawler.schema.Catalog;
import schemacrawler.schema.Column;
import schemacrawler.schema.ColumnDataType;
import schemacrawler.schema.ColumnReference;
import schemacrawler.schema.ForeignKey;
import schemacrawler.schema.ForeignKeyUpdateRule;
import schemacrawler.schema.Index;
import schemacrawler.schema.IndexColumn;
import schemacrawler.schema.PrimaryKey;
import schemacrawler.schema.Schema;
import schemacrawler.schema.Table;
import schemacrawler.schema.TableConstraintColumn;
import schemacrawler.schemacrawler.InfoLevel;
import schemacrawler.schemacrawler.LimitOptionsBuilder;
import schemacrawler.schemacrawler.LoadOptionsBuilder;
import schemacrawler.schemacrawler.SchemaCrawlerOptions;
import schemacrawler.schemacrawler.SchemaCrawlerOptionsBuilder;
import schemacrawler.schemacrawler.SchemaInfoLevelBuilder;
import schemacrawler.schemacrawler.SchemaRetrievalOptions;
import schemacrawler.schemacrawler.SchemaRetrievalOptionsBuilder;
import schemacrawler.tools.options.ConfigUtility;
import schemacrawler.tools.utility.SchemaCrawlerUtility;
import us.fatehi.utility.datasource.DatabaseConnectionSource;
import us.fatehi.utility.datasource.DatabaseConnectionSources;

/**
 * Reads a schema with SchemaCrawler and keeps only what the TestForge contract
 * model needs.
 *
 * <p>This class is the anti-corruption layer between SchemaCrawler's large
 * model and TestForge's small one. TestForge does not call
 * {@code DatabaseMetaData} itself — cross-vendor metadata reading is a solved
 * problem — but SchemaCrawler types never escape this package either.
 *
 * <p>Only base tables are crawled: views, routines, sequences, synonyms and
 * triggers are excluded at the source, so a schema full of views costs nothing.
 * The unique index that backs a primary key is dropped, because the primary key
 * already carries that information and reporting both would double every
 * primary-key change.
 */
public final class SchemaCrawlerDbSchemaInspector implements DbSchemaInspector {

    private final Pattern includeTables;
    private final Pattern excludeTables;

    public SchemaCrawlerDbSchemaInspector() {
        this(null, null);
    }

    /**
     * Creates an inspector with table filters.
     *
     * @param includeTables full-match regex against the plain table name; when
     *                      {@code null} or blank, every table is included
     * @param excludeTables full-match regex against the plain table name, applied
     *                      after {@code includeTables}; when {@code null} or
     *                      blank, nothing is excluded
     */
    public SchemaCrawlerDbSchemaInspector(String includeTables, String excludeTables) {
        this.includeTables = compile(includeTables);
        this.excludeTables = compile(excludeTables);
    }

    @Override
    public DbSchemaSnapshot inspect(DataSource dataSource, String schemaName) {
        if (dataSource == null) {
            throw new IllegalArgumentException("DataSource must not be null");
        }
        if (schemaName == null || schemaName.isBlank()) {
            throw new IllegalArgumentException(
                    "Schema name must not be null or blank. Set 'forge.db-contract.schema' "
                            + "to the schema the contract is defined on.");
        }

        Catalog catalog = crawl(dataSource, schemaName);
        Schema schema = resolveSchema(catalog, schemaName);

        List<DbTable> tables = new ArrayList<>();
        for (Table table : catalog.getTables(schema)) {
            if (table.getTableType() != null && table.getTableType().isView()) {
                continue;
            }
            if (!included(table.getName())) {
                continue;
            }
            tables.add(toTable(table, schema.getName()));
        }
        return DbSchemaSnapshot.of(schema.getName(), tables);
    }

    private Catalog crawl(DataSource dataSource, String schemaName) {
        SchemaInfoLevelBuilder infoLevel = SchemaInfoLevelBuilder.builder()
                .withInfoLevel(InfoLevel.standard)
                .setRetrieveTables(true)
                .setRetrieveTableColumns(true)
                .setRetrievePrimaryKeys(true)
                .setRetrieveForeignKeys(true)
                .setRetrieveIndexes(true)
                // partial-index WHERE clauses arrive with index information, not with
                // the bare index list
                .setRetrieveIndexInformation(true)
                .setRetrieveRoutines(false)
                .setRetrieveSequenceInformation(false)
                .setRetrieveSynonymInformation(false)
                .setRetrieveTriggerInformation(false)
                .setRetrieveViewInformation(false)
                .setRetrieveTablePrivileges(false)
                .setRetrieveTableColumnPrivileges(false)
                .setRetrieveDatabaseUsers(false);

        SchemaCrawlerOptions options = SchemaCrawlerOptionsBuilder.newSchemaCrawlerOptions()
                .withLimitOptions(LimitOptionsBuilder.builder()
                        .includeSchemas(new RegularExpressionInclusionRule(schemaPattern(schemaName)))
                        // vendors disagree on the JDBC table-type string:
                        // PostgreSQL reports "TABLE", H2 2.x reports "BASE TABLE",
                        // and a PostgreSQL declaratively partitioned parent reports
                        // "PARTITIONED TABLE" — that parent is the table consumers
                        // query, so leaving it out hid its whole contract
                        .tableTypes("TABLE", "BASE TABLE", "PARTITIONED TABLE")
                        .toOptions())
                .withLoadOptions(LoadOptionsBuilder.builder()
                        .withSchemaInfoLevelBuilder(infoLevel)
                        .toOptions());

        try {
            // Deliberately not closed, and not a leak: SchemaCrawler borrows and
            // releases one connection per retrieval step rather than holding one
            // for the whole crawl. Closing this source would call close() on the
            // caller's DataSource when it happens to be Closeable — which
            // HikariDataSource is — and shut down the application's shared pool.
            DatabaseConnectionSource connectionSource = DatabaseConnectionSources.fromDataSource(dataSource);
            return SchemaCrawlerUtility.getCatalog(
                    connectionSource, retrievalOptions(dataSource), options, ConfigUtility.newConfig());
        } catch (RuntimeException e) {
            // SchemaCrawler fails the whole crawl when the inclusion rule matches
            // nothing, so the "which schemas exist" hint belongs here too.
            throw new IllegalStateException(
                    "Failed to read schema '" + schemaName + "' from the database: " + e.getMessage()
                            + visibleSchemasHint(dataSource), e);
        }
    }

    /**
     * Retrieval options read straight from the JDBC driver instead of from
     * SchemaCrawler's per-vendor plugin registry.
     *
     * <p>Letting SchemaCrawler match the connection to a known server type makes
     * it demand that vendor's plugin on the classpath — PostgreSQL refuses to
     * crawl without {@code schemacrawler-postgresql}. Those plugins add detail
     * this bounded model does not carry, so TestForge asks the driver for the
     * table types and identifier rules it needs and stays on generic JDBC
     * retrieval for every vendor alike.
     */
    private static SchemaRetrievalOptions retrievalOptions(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return SchemaRetrievalOptionsBuilder.builder().fromConnnection(connection).toOptions();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read database metadata: " + e.getMessage(), e);
        }
    }

    /**
     * Matches a schema by its plain name or by its fully qualified
     * {@code catalog.schema} name, because vendors disagree on which of the two
     * appears in JDBC metadata.
     */
    private static Pattern schemaPattern(String schemaName) {
        return Pattern.compile("(?i)(.*\\.)?" + Pattern.quote(schemaName));
    }

    private static Schema resolveSchema(Catalog catalog, String schemaName) {
        for (Schema schema : catalog.getSchemas()) {
            if (schemaName.equalsIgnoreCase(schema.getName())) {
                return schema;
            }
        }
        List<String> crawled = catalog.getSchemas().stream().map(Schema::getName).sorted().toList();
        throw new IllegalStateException("Schema '" + schemaName + "' was not found in the database."
                + " Visible schemas: " + crawled + ". Check 'forge.db-contract.schema' and the "
                + "permissions of the connecting user.");
    }

    /**
     * Best-effort list of the schemas the connecting user can actually see. A
     * failure to build the hint must never replace the original failure, so any
     * problem here degrades to no hint at all.
     */
    private static String visibleSchemasHint(DataSource dataSource) {
        List<String> names = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             ResultSet schemas = connection.getMetaData().getSchemas()) {
            while (schemas.next()) {
                String name = schemas.getString("TABLE_SCHEM");
                if (name != null) {
                    names.add(name);
                }
            }
        } catch (SQLException | RuntimeException e) {
            return "";
        }
        Collections.sort(names);
        return " Visible schemas: " + names + ". Check 'forge.db-contract.schema' and the "
                + "permissions of the connecting user.";
    }

    private boolean included(String tableName) {
        if (includeTables != null && !includeTables.matcher(tableName).matches()) {
            return false;
        }
        return excludeTables == null || !excludeTables.matcher(tableName).matches();
    }

    private static DbTable toTable(Table table, String inspectedSchema) {
        List<DbColumn> columns = table.getColumns().stream()
                .filter(column -> !column.isHidden())
                .map(SchemaCrawlerDbSchemaInspector::toColumn)
                .toList();
        DbPrimaryKey primaryKey = toPrimaryKey(table.getPrimaryKey());
        List<DbForeignKey> foreignKeys = table.getImportedForeignKeys().stream()
                .map(foreignKey -> toForeignKey(foreignKey, inspectedSchema))
                .toList();
        List<DbIndex> indexes = table.getIndexes().stream()
                .map(SchemaCrawlerDbSchemaInspector::toIndex)
                .filter(index -> !backsPrimaryKey(index, primaryKey))
                .toList();
        return new DbTable(table.getName(), columns, primaryKey, foreignKeys, indexes);
    }

    private static DbColumn toColumn(Column column) {
        ColumnDataType dataType = column.getColumnDataType();
        ColumnTypeFamily family = ColumnTypeFamily.UNKNOWN;
        String type = "";
        if (dataType != null) {
            Integer jdbcType = dataType.getJavaSqlType() == null
                    ? null
                    : dataType.getJavaSqlType().getVendorTypeNumber();
            if (jdbcType != null) {
                family = ColumnTypeFamily.ofJdbcType(jdbcType);
            }
            type = dataType.getName();
        }
        String width = column.getWidth();
        if (width != null && !width.isBlank()) {
            type = type + width;
        }
        // An identity column carries no COLUMN_DEF — PostgreSQL keeps that in
        // pg_attribute.attidentity — so asking only for a DEFAULT clause reports
        // "no default" for a column the database populates itself, and the policy
        // then calls a harmless ADD COLUMN breaking.
        boolean databaseSupplied = column.hasDefaultValue() || column.isAutoIncremented();
        return new DbColumn(column.getName(), family, type, column.isNullable(), databaseSupplied);
    }

    private static DbPrimaryKey toPrimaryKey(PrimaryKey primaryKey) {
        if (primaryKey == null) {
            return null;
        }
        List<String> columns = primaryKey.getConstrainedColumns().stream()
                .map(TableConstraintColumn::getName)
                .toList();
        return new DbPrimaryKey(primaryKey.getName(), columns);
    }

    private static DbForeignKey toForeignKey(ForeignKey foreignKey, String inspectedSchema) {
        List<String> columns = new ArrayList<>();
        List<String> referencedColumns = new ArrayList<>();
        String referencedTable = "";
        for (ColumnReference reference : foreignKey.getColumnReferences()) {
            columns.add(reference.getForeignKeyColumn().getName());
            referencedColumns.add(reference.getPrimaryKeyColumn().getName());
            referencedTable = referencedTableName(
                    reference.getPrimaryKeyColumn().getParent(), inspectedSchema);
        }
        return new DbForeignKey(foreignKey.getName(), columns, referencedTable, referencedColumns,
                toReferentialAction(foreignKey.getDeleteRule()),
                toReferentialAction(foreignKey.getUpdateRule()));
    }

    /**
     * Names the referenced table, qualifying it with its schema when that schema
     * is not the one being inspected.
     *
     * <p>A foreign key may point outside the inspected schema, and two schemas
     * routinely hold same-named tables. Recording the bare name would make
     * {@code archive.orders} and {@code public.orders} indistinguishable, so
     * retargeting a key from one to the other — a breaking change — would diff
     * as nothing at all. Same-schema keys keep the bare name, which is both the
     * common case and the readable one.
     */
    private static String referencedTableName(Table referenced, String inspectedSchema) {
        String schemaName = referenced.getSchema() == null ? null : referenced.getSchema().getName();
        if (schemaName == null || schemaName.isBlank() || schemaName.equals(inspectedSchema)) {
            return referenced.getName();
        }
        return schemaName + "." + referenced.getName();
    }

    private static DbIndex toIndex(Index index) {
        List<String> columns = index.getColumns().stream().map(IndexColumn::getName).toList();
        String predicate = index.hasFilterCondition() ? index.getFilterCondition() : "";
        return new DbIndex(index.getName(), columns, index.isUnique(), predicate);
    }

    /**
     * Maps SchemaCrawler's referential action onto TestForge's own enum, so the
     * engine's vocabulary stops at this class like the rest of its model does.
     */
    private static DbReferentialAction toReferentialAction(ForeignKeyUpdateRule rule) {
        if (rule == null) {
            return DbReferentialAction.UNKNOWN;
        }
        return switch (rule) {
            case noAction -> DbReferentialAction.NO_ACTION;
            case restrict -> DbReferentialAction.RESTRICT;
            case cascade -> DbReferentialAction.CASCADE;
            case setNull -> DbReferentialAction.SET_NULL;
            case setDefault -> DbReferentialAction.SET_DEFAULT;
            default -> DbReferentialAction.UNKNOWN;
        };
    }

    private static boolean backsPrimaryKey(DbIndex index, DbPrimaryKey primaryKey) {
        return primaryKey != null && index.unique() && index.columns().equals(primaryKey.columns());
    }

    private static Pattern compile(String regex) {
        if (regex == null || regex.isBlank()) {
            return null;
        }
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
