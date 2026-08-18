package io.testforge.db.contract.policy;

import io.testforge.db.contract.diff.DbChange;
import io.testforge.db.contract.model.DbSchemaSnapshot;

/**
 * Turns a structural change into a compatibility verdict.
 *
 * <p>Both snapshots are passed in because some verdicts need context the change
 * itself does not carry — whether a newly added column is NOT NULL without a
 * default, for instance. Projects with their own rules register a bean of this
 * type to replace {@link DefaultDbCompatibilityPolicy}.
 */
@FunctionalInterface
public interface DbCompatibilityPolicy {

    /**
     * Classifies one change.
     *
     * @param change   the structural change to judge
     * @param baseline the baseline snapshot the change was diffed from
     * @param current  the current snapshot the change was diffed to
     * @return the change, its verdict, and the reason for it
     */
    DbChangeAssessment assess(DbChange change, DbSchemaSnapshot baseline, DbSchemaSnapshot current);
}
