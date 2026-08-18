package io.testforge.db.contract;

import io.testforge.db.contract.model.DbColumn;
import io.testforge.db.contract.model.DbForeignKey;
import io.testforge.db.contract.model.DbIndex;
import io.testforge.db.contract.model.DbPrimaryKey;
import io.testforge.db.contract.model.DbReferentialAction;
import io.testforge.db.contract.model.DbSchemaSnapshot;
import io.testforge.db.contract.model.DbTable;
import io.testforge.db.schema.ColumnTypeFamily;
import java.util.List;

/** Small hand-built snapshots so comparator and policy tests need no database. */
public final class TestSchemas {

    private TestSchemas() {
    }

    public static DbColumn column(String name, ColumnTypeFamily family, String type, boolean nullable) {
        return new DbColumn(name, family, type, nullable, false);
    }

    public static DbColumn column(String name, ColumnTypeFamily family, String type, boolean nullable, boolean hasDefault) {
        return new DbColumn(name, family, type, nullable, hasDefault);
    }

    public static DbSchemaSnapshot schema(DbTable... tables) {
        return DbSchemaSnapshot.of("public", List.of(tables));
    }

    public static DbTable table(String name, List<DbColumn> columns) {
        return new DbTable(name, columns, new DbPrimaryKey("pk_" + name, List.of("id")), List.of(), List.of());
    }

    public static DbTable table(String name, List<DbColumn> columns, DbPrimaryKey primaryKey,
                         List<DbForeignKey> foreignKeys, List<DbIndex> indexes) {
        return new DbTable(name, columns, primaryKey, foreignKeys, indexes);
    }

    /** A full (non-partial) index — the shape most tests care about. */
    public static DbIndex index(String name, List<String> columns, boolean unique) {
        return new DbIndex(name, columns, unique, "");
    }

    /** A foreign key with the database default referential actions. */
    public static DbForeignKey foreignKey(String name, List<String> columns,
                                          String referencedTable, List<String> referencedColumns) {
        return new DbForeignKey(name, columns, referencedTable, referencedColumns,
                DbReferentialAction.NO_ACTION, DbReferentialAction.NO_ACTION);
    }

    public static DbColumn id() {
        return column("id", ColumnTypeFamily.INTEGER, "int8", false);
    }
}
