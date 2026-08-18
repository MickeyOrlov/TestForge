package io.testforge.db.contract.policy;

import io.testforge.db.contract.diff.DbChange;

/**
 * One schema change plus the policy's verdict and the reason behind it.
 *
 * @param change        the structural change
 * @param compatibility the verdict
 * @param reason        why the policy reached that verdict, in one sentence
 */
public record DbChangeAssessment(DbChange change, DbCompatibility compatibility, String reason) {

    public DbChangeAssessment {
        if (change == null) {
            throw new IllegalArgumentException("Assessed change must not be null");
        }
        if (compatibility == null) {
            compatibility = DbCompatibility.UNKNOWN;
        }
        reason = reason == null ? "" : reason;
    }
}
