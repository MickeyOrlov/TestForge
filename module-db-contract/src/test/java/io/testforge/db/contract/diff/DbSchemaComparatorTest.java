package io.testforge.db.contract.diff;

import static io.testforge.db.contract.TestSchemas.column;
import static io.testforge.db.contract.TestSchemas.foreignKey;
import static io.testforge.db.contract.TestSchemas.id;
import static io.testforge.db.contract.TestSchemas.index;
import static io.testforge.db.contract.TestSchemas.schema;
import static io.testforge.db.contract.TestSchemas.table;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.testforge.db.contract.model.DbForeignKey;
import io.testforge.db.contract.model.DbIndex;
import io.testforge.db.contract.model.DbPrimaryKey;
import io.testforge.db.contract.model.DbReferentialAction;
import io.testforge.db.contract.model.DbSchemaSnapshot;
import io.testforge.db.schema.ColumnTypeFamily;
import java.util.List;
import org.junit.jupiter.api.Test;

class DbSchemaComparatorTest {

    private static final DbSchemaSnapshot ORDERS = schema(table("orders", List.of(
            id(),
            column("status", ColumnTypeFamily.CHARACTER, "varchar(32)", true))));

    @Test
    void identicalSchemas_produceNoChanges() {
        assertThat(DbSchemaComparator.compare(ORDERS, ORDERS)).isEmpty();
    }

    @Test
    void addedTable_isReportedOnceWithoutItsColumns() {
        DbSchemaSnapshot current = schema(
                table("orders", List.of(id(), column("status", ColumnTypeFamily.CHARACTER, "varchar(32)", true))),
                table("shipments", List.of(id())));

        List<DbChange> changes = DbSchemaComparator.compare(ORDERS, current);

        assertThat(changes).singleElement()
                .satisfies(change -> {
                    assertThat(change.type()).isEqualTo(DbChangeType.TABLE_ADDED);
                    assertThat(change.table()).isEqualTo("shipments");
                    assertThat(change.path()).isEqualTo("shipments");
                });
    }

    @Test
    void removedTable_isReportedOnce() {
        List<DbChange> changes = DbSchemaComparator.compare(ORDERS, schema());

        assertThat(changes).singleElement()
                .extracting(DbChange::type)
                .isEqualTo(DbChangeType.TABLE_REMOVED);
    }

    @Test
    void addedAndRemovedColumns_areReportedWithTheirDescriptions() {
        DbSchemaSnapshot current = schema(table("orders", List.of(
                id(),
                column("note", ColumnTypeFamily.CHARACTER, "varchar(10)", true))));

        List<DbChange> changes = DbSchemaComparator.compare(ORDERS, current);

        assertThat(changes).extracting(DbChange::type, DbChange::path)
                .containsExactly(
                        tuple(DbChangeType.COLUMN_ADDED, "orders.note"),
                        tuple(DbChangeType.COLUMN_REMOVED, "orders.status"));
        assertThat(changes.get(1).before()).contains("varchar(32)").contains("NULL");
    }

    @Test
    void typeFamilyChange_winsOverPhysicalTypeChange() {
        DbSchemaSnapshot current = schema(table("orders", List.of(
                id(),
                column("status", ColumnTypeFamily.INTEGER, "int4", true))));

        List<DbChange> changes = DbSchemaComparator.compare(ORDERS, current);

        assertThat(changes).singleElement()
                .satisfies(change -> {
                    assertThat(change.type()).isEqualTo(DbChangeType.COLUMN_TYPE_FAMILY_CHANGED);
                    assertThat(change.before()).isEqualTo("varchar(32) [CHARACTER]");
                    assertThat(change.after()).isEqualTo("int4 [INTEGER]");
                });
    }

    @Test
    void sameFamilyWithDifferentPhysicalType_isAPhysicalTypeChange() {
        DbSchemaSnapshot current = schema(table("orders", List.of(
                id(),
                column("status", ColumnTypeFamily.CHARACTER, "varchar(8)", true))));

        assertThat(DbSchemaComparator.compare(ORDERS, current))
                .singleElement()
                .extracting(DbChange::type)
                .isEqualTo(DbChangeType.COLUMN_PHYSICAL_TYPE_CHANGED);
    }

    @Test
    void nullabilityChanges_areReportedInBothDirections() {
        DbSchemaSnapshot tightened = schema(table("orders", List.of(
                id(), column("status", ColumnTypeFamily.CHARACTER, "varchar(32)", false))));

        assertThat(DbSchemaComparator.compare(ORDERS, tightened))
                .singleElement()
                .extracting(DbChange::type)
                .isEqualTo(DbChangeType.COLUMN_NULLABILITY_TIGHTENED);
        assertThat(DbSchemaComparator.compare(tightened, ORDERS))
                .singleElement()
                .extracting(DbChange::type)
                .isEqualTo(DbChangeType.COLUMN_NULLABILITY_RELAXED);
    }

    @Test
    void defaultChanges_areReportedInBothDirections() {
        DbSchemaSnapshot withDefault = schema(table("orders", List.of(
                id(), column("status", ColumnTypeFamily.CHARACTER, "varchar(32)", true, true))));

        assertThat(DbSchemaComparator.compare(ORDERS, withDefault))
                .singleElement()
                .extracting(DbChange::type)
                .isEqualTo(DbChangeType.COLUMN_DEFAULT_ADDED);
        assertThat(DbSchemaComparator.compare(withDefault, ORDERS))
                .singleElement()
                .extracting(DbChange::type)
                .isEqualTo(DbChangeType.COLUMN_DEFAULT_REMOVED);
    }

    @Test
    void primaryKeyRename_isNotAContractChange() {
        DbSchemaSnapshot baseline = schema(table("orders", List.of(id()),
                new DbPrimaryKey("orders_pkey", List.of("id")), List.of(), List.of()));
        DbSchemaSnapshot renamed = schema(table("orders", List.of(id()),
                new DbPrimaryKey("pk_orders", List.of("id")), List.of(), List.of()));

        assertThat(DbSchemaComparator.compare(baseline, renamed)).isEmpty();
    }

    @Test
    void primaryKeyAddedRemovedAndRekeyed_areDistinctChanges() {
        DbSchemaSnapshot none = schema(table("orders", List.of(id()), null, List.of(), List.of()));
        DbSchemaSnapshot single = schema(table("orders", List.of(id()),
                new DbPrimaryKey("pk", List.of("id")), List.of(), List.of()));
        DbSchemaSnapshot composite = schema(table("orders", List.of(id()),
                new DbPrimaryKey("pk", List.of("id", "tenant")), List.of(), List.of()));

        assertThat(DbSchemaComparator.compare(none, single)).singleElement()
                .extracting(DbChange::type).isEqualTo(DbChangeType.PRIMARY_KEY_ADDED);
        assertThat(DbSchemaComparator.compare(single, none)).singleElement()
                .extracting(DbChange::type).isEqualTo(DbChangeType.PRIMARY_KEY_REMOVED);
        assertThat(DbSchemaComparator.compare(single, composite)).singleElement()
                .extracting(DbChange::type).isEqualTo(DbChangeType.PRIMARY_KEY_COLUMNS_CHANGED);
    }

    @Test
    void foreignKeys_areMatchedByNameAndComparedByTarget() {
        DbForeignKey toCustomers = foreignKey("fk_orders_customer", List.of("customer_id"),
                "customers", List.of("id"));
        DbForeignKey toAccounts = foreignKey("fk_orders_customer", List.of("customer_id"),
                "accounts", List.of("id"));
        DbSchemaSnapshot without = schema(table("orders", List.of(id()), null, List.of(), List.of()));
        DbSchemaSnapshot with = schema(table("orders", List.of(id()), null, List.of(toCustomers), List.of()));
        DbSchemaSnapshot retargeted = schema(table("orders", List.of(id()), null, List.of(toAccounts), List.of()));

        assertThat(DbSchemaComparator.compare(without, with)).singleElement()
                .extracting(DbChange::type).isEqualTo(DbChangeType.FOREIGN_KEY_ADDED);
        assertThat(DbSchemaComparator.compare(with, without)).singleElement()
                .extracting(DbChange::type).isEqualTo(DbChangeType.FOREIGN_KEY_REMOVED);
        assertThat(DbSchemaComparator.compare(with, retargeted)).singleElement()
                .satisfies(change -> {
                    assertThat(change.type()).isEqualTo(DbChangeType.FOREIGN_KEY_CHANGED);
                    assertThat(change.before()).startsWith("(customer_id) -> customers(id)");
                    assertThat(change.after()).startsWith("(customer_id) -> accounts(id)");
                });
    }

    @Test
    void indexes_areMatchedByNameAcrossColumnsAndUniqueness() {
        DbIndex plain = index("idx_status", List.of("status"), false);
        DbIndex unique = index("idx_status", List.of("status"), true);
        DbIndex widened = index("idx_status", List.of("status", "created_at"), false);
        DbSchemaSnapshot without = schema(table("orders", List.of(id()), null, List.of(), List.of()));
        DbSchemaSnapshot with = schema(table("orders", List.of(id()), null, List.of(), List.of(plain)));

        assertThat(DbSchemaComparator.compare(without, with)).singleElement()
                .extracting(DbChange::type).isEqualTo(DbChangeType.INDEX_ADDED);
        assertThat(DbSchemaComparator.compare(with, without)).singleElement()
                .extracting(DbChange::type).isEqualTo(DbChangeType.INDEX_REMOVED);
        assertThat(DbSchemaComparator.compare(with,
                schema(table("orders", List.of(id()), null, List.of(), List.of(unique)))))
                .singleElement()
                .extracting(DbChange::type).isEqualTo(DbChangeType.INDEX_UNIQUENESS_TIGHTENED);
        assertThat(DbSchemaComparator.compare(
                schema(table("orders", List.of(id()), null, List.of(), List.of(unique))), with))
                .singleElement()
                .extracting(DbChange::type).isEqualTo(DbChangeType.INDEX_UNIQUENESS_RELAXED);
        assertThat(DbSchemaComparator.compare(with,
                schema(table("orders", List.of(id()), null, List.of(), List.of(widened)))))
                .singleElement()
                .extracting(DbChange::type).isEqualTo(DbChangeType.INDEX_COLUMNS_CHANGED);
    }

    @Test
    void droppingAnIndexPredicate_isAChange_notSilence() {
        DbIndex partial = new DbIndex("uq_status", List.of("status"), true, "deleted_at IS NULL");
        DbIndex full = new DbIndex("uq_status", List.of("status"), true, "");
        DbSchemaSnapshot before = schema(table("orders", List.of(id()), null, List.of(), List.of(partial)));
        DbSchemaSnapshot after = schema(table("orders", List.of(id()), null, List.of(), List.of(full)));

        // same name, same columns, still unique — only the scope of "unique" moved
        assertThat(DbSchemaComparator.compare(before, after)).singleElement()
                .satisfies(change -> {
                    assertThat(change.type()).isEqualTo(DbChangeType.INDEX_PREDICATE_CHANGED);
                    assertThat(change.before()).isEqualTo("WHERE deleted_at IS NULL");
                    assertThat(change.after()).isEqualTo("no predicate");
                });
        assertThat(DbSchemaComparator.compare(after, before)).singleElement()
                .extracting(DbChange::type).isEqualTo(DbChangeType.INDEX_PREDICATE_CHANGED);
    }

    @Test
    void changingAReferentialAction_isAChange_notSilence() {
        DbForeignKey cascade = new DbForeignKey("fk", List.of("customer_id"), "customers", List.of("id"),
                DbReferentialAction.CASCADE, DbReferentialAction.NO_ACTION);
        DbForeignKey restrict = new DbForeignKey("fk", List.of("customer_id"), "customers", List.of("id"),
                DbReferentialAction.RESTRICT, DbReferentialAction.NO_ACTION);
        DbSchemaSnapshot before = schema(table("orders", List.of(id()), null, List.of(cascade), List.of()));
        DbSchemaSnapshot after = schema(table("orders", List.of(id()), null, List.of(restrict), List.of()));

        // same columns, same target — what changed is what happens to this row
        assertThat(DbSchemaComparator.compare(before, after)).singleElement()
                .satisfies(change -> {
                    assertThat(change.type()).isEqualTo(DbChangeType.FOREIGN_KEY_ACTION_CHANGED);
                    assertThat(change.before()).contains("ON DELETE CASCADE");
                    assertThat(change.after()).contains("ON DELETE RESTRICT");
                });
    }

    @Test
    void aRetargetedKeyIsReportedOnce_notAlsoAsAnActionChange() {
        DbForeignKey toCustomers = new DbForeignKey("fk", List.of("customer_id"), "customers", List.of("id"),
                DbReferentialAction.CASCADE, DbReferentialAction.NO_ACTION);
        DbForeignKey toAccounts = new DbForeignKey("fk", List.of("customer_id"), "accounts", List.of("id"),
                DbReferentialAction.RESTRICT, DbReferentialAction.NO_ACTION);

        assertThat(DbSchemaComparator.compare(
                schema(table("orders", List.of(id()), null, List.of(toCustomers), List.of())),
                schema(table("orders", List.of(id()), null, List.of(toAccounts), List.of()))))
                .singleElement()
                .extracting(DbChange::type).isEqualTo(DbChangeType.FOREIGN_KEY_CHANGED);
    }

    @Test
    void changes_areOrderedDeterministicallyByTableThenObject() {
        DbSchemaSnapshot baseline = schema(
                table("orders", List.of(id(), column("a", ColumnTypeFamily.CHARACTER, "varchar", true))),
                table("customers", List.of(id(), column("z", ColumnTypeFamily.CHARACTER, "varchar", true))));
        DbSchemaSnapshot current = schema(
                table("orders", List.of(id())),
                table("customers", List.of(id())));

        assertThat(DbSchemaComparator.compare(baseline, current))
                .extracting(DbChange::path)
                .containsExactly("customers.z", "orders.a");
    }

    @Test
    void comparingAgainstAMissingSnapshot_failsLoudly() {
        assertThatThrownBy(() -> DbSchemaComparator.compare(null, ORDERS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Both snapshots");
    }
}
