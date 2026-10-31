package io.testforge.db.contract.policy;

import io.testforge.db.contract.diff.DbChange;
import java.util.List;

/**
 * One schema change plus the policy's verdict and the reason behind it.
 *
 * @param change        the structural change
 * @param compatibility the verdict
 * @param reason        why the policy reached that verdict, in one sentence
 * @param usedBy        the project's entities the change reaches, sorted; empty
 *                      when none does or no usage is known
 */
public record DbChangeAssessment(DbChange change, DbCompatibility compatibility, String reason, List<String> usedBy) {

    public DbChangeAssessment {
        if (change == null) {
            throw new IllegalArgumentException("Assessed change must not be null");
        }
        if (compatibility == null) {
            compatibility = DbCompatibility.UNKNOWN;
        }
        reason = reason == null ? "" : reason;
        usedBy = usedBy == null ? List.of() : List.copyOf(usedBy);
    }

    public DbChangeAssessment(DbChange change, DbCompatibility compatibility, String reason) {
        this(change, compatibility, reason, List.of());
    }

    /** The same verdict, with the entities it reaches. */
    public DbChangeAssessment withUsedBy(List<String> users) {
        return new DbChangeAssessment(change, compatibility, reason, users);
    }
}
