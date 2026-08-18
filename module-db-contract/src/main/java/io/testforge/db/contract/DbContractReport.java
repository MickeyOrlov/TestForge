package io.testforge.db.contract;

import io.testforge.db.contract.policy.DbChangeAssessment;
import io.testforge.db.contract.policy.DbCompatibility;
import java.util.List;

/**
 * Result of one database contract check.
 *
 * @param enabled          whether the check was enabled; a disabled check reports no changes
 * @param generatedAt      when the check ran, ISO-8601
 * @param schema           the inspected schema
 * @param baselinePresent  whether a baseline snapshot existed; without one there is
 *                         nothing to compare and {@code changes} is empty
 * @param compatible       whether the check passed the configured gate
 * @param worstClassified  the most severe verdict on the NON_BREAKING/RISKY/BREAKING
 *                         axis; {@code UNKNOWN} changes are not a severity and never
 *                         contribute — count them through {@code unknownCount}
 * @param breakingCount    number of {@code BREAKING} changes
 * @param riskyCount       number of {@code RISKY} changes
 * @param unknownCount     number of {@code UNKNOWN} changes
 * @param nonBreakingCount number of {@code NON_BREAKING} changes
 * @param changes          every detected change with its verdict and reason
 * @param baselineSnapshot path of the baseline snapshot
 * @param currentSnapshot  path of the snapshot captured by this run
 * @param reportJson       path of the JSON report
 * @param reportMarkdown   path of the Markdown report
 */
public record DbContractReport(
        boolean enabled,
        String generatedAt,
        String schema,
        boolean baselinePresent,
        boolean compatible,
        DbCompatibility worstClassified,
        int breakingCount,
        int riskyCount,
        int unknownCount,
        int nonBreakingCount,
        List<DbChangeAssessment> changes,
        String baselineSnapshot,
        String currentSnapshot,
        String reportJson,
        String reportMarkdown) {

    public DbContractReport {
        changes = changes == null ? List.of() : List.copyOf(changes);
        if (worstClassified == null || !worstClassified.classified()) {
            worstClassified = DbCompatibility.NON_BREAKING;
        }
    }

    /**
     * The changes carrying one verdict.
     *
     * @param compatibility the verdict to filter by
     * @return the matching assessments, in report order
     */
    public List<DbChangeAssessment> changesWith(DbCompatibility compatibility) {
        return changes.stream().filter(change -> change.compatibility() == compatibility).toList();
    }
}
