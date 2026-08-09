package io.testforge.api.fuzz;

/**
 * Thrown by {@code ApiFuzzRunner.assertHealthy()}. The message names the
 * findings and the case id for each, so a CI log is enough to start
 * reproducing without opening an artifact.
 */
public class ApiFuzzException extends RuntimeException {

    private final transient ApiFuzzReport report;

    public ApiFuzzException(ApiFuzzReport report) {
        super(message(report));
        this.report = report;
    }

    public ApiFuzzReport report() {
        return report;
    }

    private static String message(ApiFuzzReport report) {
        StringBuilder message = new StringBuilder("API fuzzing found problems (seed ")
                .append(report.seed())
                .append("). See ")
                .append(report.reportMarkdown())
                .append('\n');

        report.findings().forEach(finding -> {
            message.append("  - ").append(finding.verdict());
            finding.evidence().stream()
                    .filter(evidence -> evidence.kind().reportable())
                    .forEach(evidence -> message.append(' ').append(evidence.kind()));
            message.append(' ').append(finding.fuzzCase().operationKey())
                    .append(" [").append(finding.fuzzCase().id()).append("]\n");
        });
        return message.toString();
    }
}
