package io.testforge.db.contract;

import io.testforge.db.contract.policy.DbChangeAssessment;
import java.util.stream.Collectors;

/**
 * Thrown by {@link DbContractRunner#assertCompatible()} when the detected schema
 * changes fail the configured gate. The message lists the offending changes so a
 * CI log explains the failure without opening the artifacts.
 */
public class DbContractException extends RuntimeException {

    private final transient DbContractReport report;

    public DbContractException(DbContractReport report) {
        super(buildMessage(report));
        this.report = report;
    }

    /**
     * The report that failed the gate.
     *
     * @return the report
     */
    public DbContractReport report() {
        return report;
    }

    private static String buildMessage(DbContractReport report) {
        String offenders = report.changes().stream()
                .map(assessment -> "  - [" + assessment.compatibility() + "] "
                        + assessment.change().type() + " " + assessment.change().path()
                        + ": " + assessment.reason())
                .collect(Collectors.joining(System.lineSeparator()));
        return "Database contract check failed for schema '" + report.schema() + "': "
                + report.breakingCount() + " breaking, "
                + report.riskyCount() + " risky, "
                + report.unknownCount() + " unknown change(s)."
                + System.lineSeparator() + offenders
                + System.lineSeparator() + "Report: " + report.reportMarkdown();
    }
}
