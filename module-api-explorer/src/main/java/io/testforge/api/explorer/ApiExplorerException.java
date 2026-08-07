package io.testforge.api.explorer;

/**
 * Thrown by {@code ApiExplorerRunner.assertHealthy()} so a JUnit job fails
 * loudly. Carries the report: the message says how many operations went wrong,
 * the artifacts say what each one did.
 */
public class ApiExplorerException extends RuntimeException {

    private final transient ApiExplorerReport report;

    public ApiExplorerException(ApiExplorerReport report) {
        super(message(report));
        this.report = report;
    }

    public ApiExplorerReport report() {
        return report;
    }

    private static String message(ApiExplorerReport report) {
        StringBuilder message = new StringBuilder("API exploration failed. See ")
                .append(report.reportMarkdown())
                .append('\n');

        for (SpecExplorationReport spec : report.failingSpecs()) {
            message.append("  - ").append(spec.specId());
            if (spec.error() != null) {
                message.append(": ").append(spec.error());
            } else {
                message.append(": ")
                        .append(spec.failedCalls()).append(" failed, ")
                        .append(spec.contractMismatch()).append(" contract mismatches");
            }
            message.append('\n');
        }
        return message.toString();
    }
}
