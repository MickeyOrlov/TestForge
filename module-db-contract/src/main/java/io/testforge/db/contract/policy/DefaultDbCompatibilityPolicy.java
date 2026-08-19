package io.testforge.db.contract.policy;

import io.testforge.db.contract.diff.DbChange;
import io.testforge.db.contract.model.DbColumn;
import io.testforge.db.contract.model.DbForeignKey;
import io.testforge.db.contract.model.DbIndex;
import io.testforge.db.contract.model.DbReferentialAction;
import io.testforge.db.contract.model.DbSchemaSnapshot;
import io.testforge.db.schema.ColumnTypeFamily;
import java.util.Optional;

/**
 * TestForge's v1 compatibility rules.
 *
 * <p>The "consumer" the rules protect is a test suite or service that reads and
 * writes this schema by column name. The rules are deliberately small and fixed;
 * they are not an attempt to reproduce a migration linter. Every verdict carries
 * a reason so a failing CI gate explains itself.
 *
 * <table>
 *   <caption>v1 rules</caption>
 *   <tr><th>Change</th><th>Verdict</th></tr>
 *   <tr><td>Table or column removed</td><td>BREAKING</td></tr>
 *   <tr><td>Logical type family changed</td><td>BREAKING (UNKNOWN when either side is an unmapped type)</td></tr>
 *   <tr><td>Physical type changed within the same family</td><td>RISKY — widening and narrowing are not told apart in v1</td></tr>
 *   <tr><td>Column added: nullable</td><td>NON_BREAKING</td></tr>
 *   <tr><td>Column added: NOT NULL with a default</td><td>RISKY</td></tr>
 *   <tr><td>Column added: NOT NULL without a default</td><td>BREAKING</td></tr>
 *   <tr><td>nullable &rarr; NOT NULL</td><td>BREAKING — writers that omit the column now fail</td></tr>
 *   <tr><td>NOT NULL &rarr; nullable</td><td>RISKY — readers lose a guarantee they may rely on</td></tr>
 *   <tr><td>Primary key removed or re-keyed</td><td>BREAKING</td></tr>
 *   <tr><td>Primary key added</td><td>RISKY</td></tr>
 *   <tr><td>Foreign key added, removed or retargeted</td><td>RISKY, RISKY, BREAKING</td></tr>
 *   <tr><td>Index added (non-unique)</td><td>NON_BREAKING</td></tr>
 *   <tr><td>Index added (unique), dropped, re-columned, uniqueness flipped</td><td>RISKY</td></tr>
 * </table>
 */
public final class DefaultDbCompatibilityPolicy implements DbCompatibilityPolicy {

    @Override
    public DbChangeAssessment assess(DbChange change, DbSchemaSnapshot baseline, DbSchemaSnapshot current) {
        if (change == null) {
            throw new IllegalArgumentException("Change to assess must not be null");
        }
        return switch (change.type()) {
            case TABLE_ADDED -> nonBreaking(change,
                    "A new table cannot break a consumer that does not read it yet.");
            case TABLE_REMOVED -> breaking(change,
                    "Every consumer reading this table now fails.");

            case COLUMN_ADDED -> addedColumn(change, current);
            case COLUMN_REMOVED -> breaking(change,
                    "Every consumer selecting this column now fails.");

            case COLUMN_TYPE_FAMILY_CHANGED -> typeFamilyChanged(change, baseline, current);
            case COLUMN_PHYSICAL_TYPE_CHANGED -> physicalTypeChanged(change, baseline, current);

            case COLUMN_NULLABILITY_TIGHTENED -> breaking(change,
                    "Writers that omit this column, or write NULL into it, now fail.");
            case COLUMN_NULLABILITY_RELAXED -> risky(change,
                    "Readers that relied on this column always having a value can now get NULL, "
                            + "even though nothing that used to be written is rejected.");

            case COLUMN_DEFAULT_ADDED -> nonBreaking(change,
                    "A new default only affects writes that omit the column.");
            case COLUMN_DEFAULT_REMOVED -> risky(change,
                    "Writers that relied on the default now insert NULL, or fail when the column is NOT NULL.");

            case PRIMARY_KEY_ADDED -> risky(change,
                    "A new primary key adds a uniqueness and NOT NULL constraint that existing writes can violate.");
            case PRIMARY_KEY_REMOVED -> breaking(change,
                    "Consumers relying on row identity or on the key's uniqueness guarantee lose it.");
            case PRIMARY_KEY_COLUMNS_CHANGED -> breaking(change,
                    "Row identity changed, so lookups and joins on the old key are no longer valid.");

            case FOREIGN_KEY_ADDED -> risky(change,
                    "A new referential constraint can reject writes that used to succeed.");
            case FOREIGN_KEY_REMOVED -> risky(change,
                    "The referential integrity guarantee consumers relied on is gone.");
            case FOREIGN_KEY_CHANGED -> breaking(change,
                    "The relationship now points somewhere else, so joins through it change meaning.");
            case FOREIGN_KEY_ACTION_CHANGED -> referentialActionChanged(change, baseline, current);

            case INDEX_ADDED -> addedIndex(change, current);
            case INDEX_REMOVED -> removedIndex(change, baseline);
            case INDEX_COLUMNS_CHANGED -> risky(change,
                    "The index covers different columns, so any uniqueness or access path it backed changed.");
            case INDEX_UNIQUENESS_TIGHTENED -> risky(change,
                    "A newly unique index can reject writes that used to succeed.");
            case INDEX_UNIQUENESS_RELAXED -> risky(change,
                    "The uniqueness guarantee consumers relied on is gone.");
            case INDEX_PREDICATE_CHANGED -> indexPredicateChanged(change, current);
        };
    }

    private DbChangeAssessment addedColumn(DbChange change, DbSchemaSnapshot current) {
        Optional<DbColumn> column = column(current, change);
        if (column.isEmpty()) {
            return unknown(change, "The added column could not be resolved in the current snapshot.");
        }
        DbColumn added = column.get();
        if (added.nullable()) {
            return nonBreaking(change, "A new nullable column leaves existing reads and writes valid.");
        }
        if (added.hasDefault()) {
            return risky(change,
                    "The database supplies this column, so existing writes still succeed, "
                            + "but every existing row was backfilled with a value nobody chose.");
        }
        return breaking(change, "Existing INSERTs that do not name this column now fail.");
    }

    private DbChangeAssessment typeFamilyChanged(DbChange change, DbSchemaSnapshot baseline,
                                                 DbSchemaSnapshot current) {
        boolean unmapped = column(baseline, change)
                .map(column -> column.typeFamily() == ColumnTypeFamily.UNKNOWN)
                .orElse(true)
                || column(current, change)
                .map(column -> column.typeFamily() == ColumnTypeFamily.UNKNOWN)
                .orElse(true);
        if (unmapped) {
            return unknown(change,
                    "One side of this change is a type TestForge does not map, so the impact is not judged.");
        }
        return breaking(change, "Consumers reading or binding this column get a different Java type.");
    }

    private DbChangeAssessment physicalTypeChanged(DbChange change, DbSchemaSnapshot baseline,
                                                   DbSchemaSnapshot current) {
        boolean unmapped = column(current, change)
                .map(column -> column.typeFamily() == ColumnTypeFamily.UNKNOWN)
                .orElse(true);
        if (unmapped) {
            return unknown(change,
                    "This is a vendor-specific type TestForge does not map, so the impact is not judged.");
        }
        return risky(change,
                "The logical type family is unchanged, but v1 does not tell a widening from a narrowing — "
                        + "check whether existing values still fit.");
    }

    /**
     * A referential action that starts rejecting the parent change is breaking
     * for the same reason tightening nullability is: an operation that used to
     * succeed now fails. Any other move changes what the database silently does
     * to rows, which is a risk rather than a rejection.
     */
    private DbChangeAssessment referentialActionChanged(DbChange change, DbSchemaSnapshot baseline,
                                                        DbSchemaSnapshot current) {
        Optional<DbForeignKey> before = foreignKey(baseline, change);
        Optional<DbForeignKey> now = foreignKey(current, change);
        if (before.isEmpty() || now.isEmpty()) {
            return unknown(change, "The changed foreign key could not be resolved in both snapshots.");
        }
        // Only the action that actually moved is judged — in both dimensions of
        // this decision. NO_ACTION rejects by definition, so asking "does any
        // current action reject?" would blame an untouched ON UPDATE default for
        // a breaking change; and asking "is any action unmapped?" would let an
        // unmapped ON UPDATE that never moved downgrade a plain CASCADE ->
        // RESTRICT to UNKNOWN, which the default gate does not fail on.
        boolean deleteStartedRejecting = startedRejecting(before.get().onDelete(), now.get().onDelete());
        boolean updateStartedRejecting = startedRejecting(before.get().onUpdate(), now.get().onUpdate());
        if (deleteStartedRejecting || updateStartedRejecting) {
            return breaking(change,
                    "Deleting or re-keying a referenced row is now rejected while children exist, "
                            + "so writes that used to succeed fail.");
        }
        if (changedIntoOrOutOfUnmapped(before.get(), now.get())) {
            return unknown(change,
                    "The driver reported a referential action TestForge does not map, so the impact is not judged.");
        }
        return risky(change,
                "The database now does something different to referencing rows — they can be removed or "
                        + "blanked without the consumer asking.");
    }

    /**
     * An action only "started rejecting" when it moved and TestForge understands
     * both ends of that move; an unmapped starting point is no basis for claiming
     * writes began to fail.
     */
    private static boolean startedRejecting(DbReferentialAction before, DbReferentialAction now) {
        return before != now
                && before != DbReferentialAction.UNKNOWN
                && now != DbReferentialAction.UNKNOWN
                && rejects(now)
                && !rejects(before);
    }

    /** Whether an action that actually moved has an unmapped value on either end. */
    private static boolean changedIntoOrOutOfUnmapped(DbForeignKey before, DbForeignKey now) {
        return unmappedMove(before.onDelete(), now.onDelete())
                || unmappedMove(before.onUpdate(), now.onUpdate());
    }

    private static boolean unmappedMove(DbReferentialAction before, DbReferentialAction now) {
        return before != now
                && (before == DbReferentialAction.UNKNOWN || now == DbReferentialAction.UNKNOWN);
    }

    private static boolean rejects(DbReferentialAction action) {
        return action == DbReferentialAction.RESTRICT || action == DbReferentialAction.NO_ACTION;
    }

    /**
     * The predicate decides which rows an index covers, so on a unique index it
     * decides what "unique" even means.
     */
    private DbChangeAssessment indexPredicateChanged(DbChange change, DbSchemaSnapshot current) {
        boolean unique = index(current, change).map(DbIndex::unique).orElse(false);
        return unique
                ? risky(change, "The uniqueness constraint now covers a different set of rows, so it can "
                        + "reject writes it used to allow, or allow ones it used to reject.")
                : risky(change, "The index covers a different set of rows, so queries that relied on this "
                        + "access path can stop using it.");
    }

    private Optional<DbForeignKey> foreignKey(DbSchemaSnapshot snapshot, DbChange change) {
        if (snapshot == null || change.object() == null) {
            return Optional.empty();
        }
        return snapshot.table(change.table()).stream()
                .flatMap(table -> table.foreignKeys().stream())
                .filter(foreignKey -> foreignKey.name().equals(change.object()))
                .findFirst();
    }

    private DbChangeAssessment addedIndex(DbChange change, DbSchemaSnapshot current) {
        return index(current, change).filter(DbIndex::unique).isPresent()
                ? risky(change, "A new unique index can reject writes that used to succeed.")
                : nonBreaking(change, "A new non-unique index constrains nothing.");
    }

    private DbChangeAssessment removedIndex(DbChange change, DbSchemaSnapshot baseline) {
        return index(baseline, change).filter(DbIndex::unique).isPresent()
                ? risky(change, "The uniqueness guarantee consumers relied on is gone.")
                : risky(change, "Queries that relied on this access path can get slow enough to time out.");
    }

    private Optional<DbColumn> column(DbSchemaSnapshot snapshot, DbChange change) {
        if (snapshot == null || change.object() == null) {
            return Optional.empty();
        }
        return snapshot.table(change.table()).flatMap(table -> table.column(change.object()));
    }

    private Optional<DbIndex> index(DbSchemaSnapshot snapshot, DbChange change) {
        if (snapshot == null || change.object() == null) {
            return Optional.empty();
        }
        return snapshot.table(change.table()).stream()
                .flatMap(table -> table.indexes().stream())
                .filter(index -> index.name().equals(change.object()))
                .findFirst();
    }

    private DbChangeAssessment breaking(DbChange change, String reason) {
        return new DbChangeAssessment(change, DbCompatibility.BREAKING, reason);
    }

    private DbChangeAssessment risky(DbChange change, String reason) {
        return new DbChangeAssessment(change, DbCompatibility.RISKY, reason);
    }

    private DbChangeAssessment nonBreaking(DbChange change, String reason) {
        return new DbChangeAssessment(change, DbCompatibility.NON_BREAKING, reason);
    }

    private DbChangeAssessment unknown(DbChange change, String reason) {
        return new DbChangeAssessment(change, DbCompatibility.UNKNOWN, reason);
    }
}
