package io.testforge.db.contract.policy;

import static io.testforge.db.contract.TestSchemas.column;
import static io.testforge.db.contract.TestSchemas.foreignKey;
import static io.testforge.db.contract.TestSchemas.id;
import static io.testforge.db.contract.TestSchemas.index;
import static io.testforge.db.contract.TestSchemas.schema;
import static io.testforge.db.contract.TestSchemas.table;
import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.db.contract.diff.DbChange;
import io.testforge.db.contract.diff.DbChangeType;
import io.testforge.db.contract.diff.DbSchemaComparator;
import io.testforge.db.contract.model.DbForeignKey;
import io.testforge.db.contract.model.DbIndex;
import io.testforge.db.contract.model.DbPrimaryKey;
import io.testforge.db.contract.model.DbReferentialAction;
import io.testforge.db.contract.model.DbSchemaSnapshot;
import io.testforge.db.schema.ColumnTypeFamily;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultDbCompatibilityPolicyTest {

    private final DefaultDbCompatibilityPolicy policy = new DefaultDbCompatibilityPolicy();

    private DbChangeAssessment only(DbSchemaSnapshot baseline, DbSchemaSnapshot current) {
        List<DbChange> changes = DbSchemaComparator.compare(baseline, current);
        assertThat(changes).hasSize(1);
        return policy.assess(changes.get(0), baseline, current);
    }

    @Test
    void addedNullableColumn_isNonBreaking() {
        DbSchemaSnapshot baseline = schema(table("orders", List.of(id())));
        DbSchemaSnapshot current = schema(table("orders", List.of(id(),
                column("note", ColumnTypeFamily.CHARACTER, "varchar(64)", true))));

        assertThat(only(baseline, current).compatibility()).isEqualTo(DbCompatibility.NON_BREAKING);
    }

    @Test
    void addedNotNullColumnWithoutDefault_isBreakingAndSaysWhy() {
        DbSchemaSnapshot baseline = schema(table("orders", List.of(id())));
        DbSchemaSnapshot current = schema(table("orders", List.of(id(),
                column("tenant", ColumnTypeFamily.CHARACTER, "varchar(64)", false, false))));

        DbChangeAssessment assessment = only(baseline, current);

        assertThat(assessment.compatibility()).isEqualTo(DbCompatibility.BREAKING);
        assertThat(assessment.reason()).contains("INSERT");
    }

    @Test
    void addedNotNullColumnWithDefault_isRisky() {
        DbSchemaSnapshot baseline = schema(table("orders", List.of(id())));
        DbSchemaSnapshot current = schema(table("orders", List.of(id(),
                column("tenant", ColumnTypeFamily.CHARACTER, "varchar(64)", false, true))));

        assertThat(only(baseline, current).compatibility()).isEqualTo(DbCompatibility.RISKY);
    }

    @Test
    void droppedColumnAndDroppedTable_areBreaking() {
        DbSchemaSnapshot baseline = schema(table("orders", List.of(id(),
                column("note", ColumnTypeFamily.CHARACTER, "varchar(64)", true))));

        assertThat(only(baseline, schema(table("orders", List.of(id())))).compatibility())
                .isEqualTo(DbCompatibility.BREAKING);
        assertThat(only(baseline, schema()).compatibility()).isEqualTo(DbCompatibility.BREAKING);
    }

    @Test
    void typeFamilyChangeBetweenKnownFamilies_isBreaking() {
        DbSchemaSnapshot baseline = schema(table("orders", List.of(id(),
                column("amount", ColumnTypeFamily.INTEGER, "int4", true))));
        DbSchemaSnapshot current = schema(table("orders", List.of(id(),
                column("amount", ColumnTypeFamily.CHARACTER, "varchar(16)", true))));

        assertThat(only(baseline, current).compatibility()).isEqualTo(DbCompatibility.BREAKING);
    }

    @Test
    void typeChangeInvolvingAnUnmappedType_isUnknownRatherThanGuessed() {
        DbSchemaSnapshot baseline = schema(table("orders", List.of(id(),
                column("payload", ColumnTypeFamily.UNKNOWN, "jsonb", true))));
        DbSchemaSnapshot current = schema(table("orders", List.of(id(),
                column("payload", ColumnTypeFamily.CHARACTER, "text", true))));

        DbChangeAssessment assessment = only(baseline, current);

        assertThat(assessment.compatibility()).isEqualTo(DbCompatibility.UNKNOWN);
        assertThat(assessment.reason()).contains("does not map");
    }

    @Test
    void physicalTypeChangeWithinAFamily_isRiskyBecauseWideningIsNotAnalysed() {
        DbSchemaSnapshot baseline = schema(table("orders", List.of(id(),
                column("code", ColumnTypeFamily.CHARACTER, "varchar(64)", true))));
        DbSchemaSnapshot current = schema(table("orders", List.of(id(),
                column("code", ColumnTypeFamily.CHARACTER, "varchar(8)", true))));

        assertThat(only(baseline, current).compatibility()).isEqualTo(DbCompatibility.RISKY);
    }

    @Test
    void physicalTypeChangeOfAnUnmappedType_isUnknown() {
        DbSchemaSnapshot baseline = schema(table("orders", List.of(id(),
                column("payload", ColumnTypeFamily.UNKNOWN, "json", true))));
        DbSchemaSnapshot current = schema(table("orders", List.of(id(),
                column("payload", ColumnTypeFamily.UNKNOWN, "jsonb", true))));

        assertThat(only(baseline, current).compatibility()).isEqualTo(DbCompatibility.UNKNOWN);
    }

    @Test
    void tighteningNullability_isBreakingForWriters() {
        DbSchemaSnapshot nullable = schema(table("orders", List.of(id(),
                column("code", ColumnTypeFamily.CHARACTER, "varchar(8)", true))));
        DbSchemaSnapshot notNull = schema(table("orders", List.of(id(),
                column("code", ColumnTypeFamily.CHARACTER, "varchar(8)", false))));

        assertThat(only(nullable, notNull).compatibility()).isEqualTo(DbCompatibility.BREAKING);
    }

    @Test
    void relaxingNullability_isRiskyBecauseReadersLoseAGuarantee() {
        DbSchemaSnapshot notNull = schema(table("orders", List.of(id(),
                column("code", ColumnTypeFamily.CHARACTER, "varchar(8)", false))));
        DbSchemaSnapshot nullable = schema(table("orders", List.of(id(),
                column("code", ColumnTypeFamily.CHARACTER, "varchar(8)", true))));

        DbChangeAssessment assessment = only(notNull, nullable);

        assertThat(assessment.change().type()).isEqualTo(DbChangeType.COLUMN_NULLABILITY_RELAXED);
        assertThat(assessment.compatibility())
                .as("the contract protects readers as well as writers")
                .isEqualTo(DbCompatibility.RISKY);
        assertThat(assessment.reason()).contains("NULL");
    }

    @Test
    void primaryKeyVerdicts_followTheDirectionOfTheChange() {
        DbSchemaSnapshot none = schema(table("orders", List.of(id()), null, List.of(), List.of()));
        DbSchemaSnapshot single = schema(table("orders", List.of(id()),
                new DbPrimaryKey("pk", List.of("id")), List.of(), List.of()));
        DbSchemaSnapshot composite = schema(table("orders", List.of(id()),
                new DbPrimaryKey("pk", List.of("id", "tenant")), List.of(), List.of()));

        assertThat(only(none, single).compatibility()).isEqualTo(DbCompatibility.RISKY);
        assertThat(only(single, none).compatibility()).isEqualTo(DbCompatibility.BREAKING);
        assertThat(only(single, composite).compatibility()).isEqualTo(DbCompatibility.BREAKING);
    }

    @Test
    void foreignKeyVerdicts_separateRetargetingFromAddingAndDropping() {
        DbSchemaSnapshot without = schema(table("orders", List.of(id()), null, List.of(), List.of()));
        DbSchemaSnapshot with = schema(table("orders", List.of(id()), null,
                List.of(foreignKey("fk", List.of("customer_id"), "customers", List.of("id"))), List.of()));
        DbSchemaSnapshot retargeted = schema(table("orders", List.of(id()), null,
                List.of(foreignKey("fk", List.of("customer_id"), "accounts", List.of("id"))), List.of()));

        assertThat(only(without, with).compatibility()).isEqualTo(DbCompatibility.RISKY);
        assertThat(only(with, without).compatibility()).isEqualTo(DbCompatibility.RISKY);
        assertThat(only(with, retargeted).compatibility()).isEqualTo(DbCompatibility.BREAKING);
    }

    @Test
    void addingANonUniqueIndexIsFree_addingAUniqueOneIsNot() {
        DbSchemaSnapshot without = schema(table("orders", List.of(id()), null, List.of(), List.of()));
        DbSchemaSnapshot plain = schema(table("orders", List.of(id()), null, List.of(),
                List.of(index("idx", List.of("status"), false))));
        DbSchemaSnapshot unique = schema(table("orders", List.of(id()), null, List.of(),
                List.of(index("idx", List.of("status"), true))));

        assertThat(only(without, plain).compatibility()).isEqualTo(DbCompatibility.NON_BREAKING);
        assertThat(only(without, unique).compatibility()).isEqualTo(DbCompatibility.RISKY);
    }

    @Test
    void droppingAnIndex_isRiskyWhicheverKindItWas() {
        DbSchemaSnapshot without = schema(table("orders", List.of(id()), null, List.of(), List.of()));
        DbSchemaSnapshot plain = schema(table("orders", List.of(id()), null, List.of(),
                List.of(index("idx", List.of("status"), false))));
        DbSchemaSnapshot unique = schema(table("orders", List.of(id()), null, List.of(),
                List.of(index("idx", List.of("status"), true))));

        assertThat(only(plain, without).compatibility()).isEqualTo(DbCompatibility.RISKY);
        assertThat(only(unique, without).compatibility()).isEqualTo(DbCompatibility.RISKY);
        assertThat(only(unique, without).reason()).contains("uniqueness");
    }

    @Test
    void addedTable_isNonBreaking() {
        DbSchemaSnapshot baseline = schema(table("orders", List.of(id())));
        DbSchemaSnapshot current = schema(table("orders", List.of(id())), table("shipments", List.of(id())));

        assertThat(only(baseline, current).compatibility()).isEqualTo(DbCompatibility.NON_BREAKING);
    }

    @Test
    void addingADefaultIsFree_removingOneIsRisky() {
        DbSchemaSnapshot without = schema(table("orders", List.of(id(),
                column("status", ColumnTypeFamily.CHARACTER, "varchar(8)", true, false))));
        DbSchemaSnapshot with = schema(table("orders", List.of(id(),
                column("status", ColumnTypeFamily.CHARACTER, "varchar(8)", true, true))));

        assertThat(only(without, with).compatibility()).isEqualTo(DbCompatibility.NON_BREAKING);
        assertThat(only(with, without).compatibility()).isEqualTo(DbCompatibility.RISKY);
    }

    @Test
    void reshapingAnExistingIndex_isRiskyWhicheverWayItMoves() {
        DbSchemaSnapshot plain = schema(table("orders", List.of(id()), null, List.of(),
                List.of(index("idx", List.of("status"), false))));
        DbSchemaSnapshot unique = schema(table("orders", List.of(id()), null, List.of(),
                List.of(index("idx", List.of("status"), true))));
        DbSchemaSnapshot widened = schema(table("orders", List.of(id()), null, List.of(),
                List.of(index("idx", List.of("status", "created_at"), false))));

        assertThat(only(plain, unique).compatibility()).isEqualTo(DbCompatibility.RISKY);
        assertThat(only(unique, plain).compatibility()).isEqualTo(DbCompatibility.RISKY);
        assertThat(only(plain, widened).compatibility()).isEqualTo(DbCompatibility.RISKY);
    }

    @Test
    void wideningAType_getsTheSameVerdictAsNarrowingIt() {
        DbSchemaSnapshot narrow = schema(table("orders", List.of(id(),
                column("code", ColumnTypeFamily.CHARACTER, "varchar(8)", true))));
        DbSchemaSnapshot wide = schema(table("orders", List.of(id(),
                column("code", ColumnTypeFamily.CHARACTER, "varchar(64)", true))));

        // the README promises v1 does not tell the two apart; if that ever changes
        // it must change deliberately, not by accident
        assertThat(only(narrow, wide).compatibility()).isEqualTo(DbCompatibility.RISKY);
        assertThat(only(wide, narrow).compatibility()).isEqualTo(DbCompatibility.RISKY);
    }

    @Test
    void anIndexPredicateChange_isRiskyInBothDirections() {
        DbSchemaSnapshot partial = schema(table("orders", List.of(id()), null, List.of(),
                List.of(new DbIndex("uq_status", List.of("status"), true, "deleted_at IS NULL"))));
        DbSchemaSnapshot full = schema(table("orders", List.of(id()), null, List.of(),
                List.of(new DbIndex("uq_status", List.of("status"), true, ""))));

        assertThat(only(partial, full).compatibility()).isEqualTo(DbCompatibility.RISKY);
        assertThat(only(full, partial).compatibility()).isEqualTo(DbCompatibility.RISKY);
        assertThat(only(partial, full).reason()).contains("different set of rows");
    }

    @Test
    void anActionThatStartsRejectingWrites_isBreaking() {
        DbSchemaSnapshot cascade = withAction(DbReferentialAction.CASCADE);
        DbSchemaSnapshot restrict = withAction(DbReferentialAction.RESTRICT);

        DbChangeAssessment assessment = only(cascade, restrict);

        assertThat(assessment.compatibility())
                .as("a delete that used to succeed now fails, exactly like tightening nullability")
                .isEqualTo(DbCompatibility.BREAKING);
        assertThat(assessment.reason()).contains("used to succeed");
    }

    @Test
    void anActionThatStartsRemovingRowsSilently_isRisky() {
        assertThat(only(withAction(DbReferentialAction.RESTRICT), withAction(DbReferentialAction.CASCADE))
                .compatibility()).isEqualTo(DbCompatibility.RISKY);
        assertThat(only(withAction(DbReferentialAction.RESTRICT), withAction(DbReferentialAction.SET_NULL))
                .compatibility()).isEqualTo(DbCompatibility.RISKY);
    }

    @Test
    void anUnmappedReferentialAction_isUnknownRatherThanGuessed() {
        assertThat(only(withAction(DbReferentialAction.CASCADE), withAction(DbReferentialAction.UNKNOWN))
                .compatibility()).isEqualTo(DbCompatibility.UNKNOWN);
    }

    private static DbSchemaSnapshot withAction(DbReferentialAction onDelete) {
        return schema(table("orders", List.of(id()), null,
                List.of(new DbForeignKey("fk", List.of("customer_id"), "customers", List.of("id"),
                        onDelete, DbReferentialAction.NO_ACTION)),
                List.of()));
    }

    @Test
    void everyVerdictCarriesAReason() {
        DbSchemaSnapshot baseline = schema(table("orders", List.of(id(),
                column("note", ColumnTypeFamily.CHARACTER, "varchar(8)", true))));
        DbSchemaSnapshot current = schema(table("orders", List.of(id())), table("shipments", List.of(id())));

        for (DbChange change : DbSchemaComparator.compare(baseline, current)) {
            assertThat(policy.assess(change, baseline, current).reason()).isNotBlank();
        }
    }

    @Test
    void unknownIsNotASeverity_soItIsNeverRankedAgainstTheOthers() {
        assertThat(DbCompatibility.UNKNOWN.classified()).isFalse();
        assertThat(DbCompatibility.NON_BREAKING.classified()).isTrue();
        assertThat(DbCompatibility.RISKY.classified()).isTrue();
        assertThat(DbCompatibility.BREAKING.classified()).isTrue();
    }

    @Test
    void theSeverityAxisIsOrderedNonBreakingThenRiskyThenBreaking() {
        assertThat(DbCompatibility.NON_BREAKING).isLessThan(DbCompatibility.RISKY);
        assertThat(DbCompatibility.RISKY).isLessThan(DbCompatibility.BREAKING);
    }
}
