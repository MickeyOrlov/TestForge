package io.testforge.api.codegen;

public class ApiCodegenException extends AssertionError {

    private final ApiCodegenReport report;

    public ApiCodegenException(ApiCodegenReport report) {
        super(message(report));
        this.report = report;
    }

    public ApiCodegenReport report() {
        return report;
    }

    private static String message(ApiCodegenReport report) {
        StringBuilder message = new StringBuilder("API code generation failed");
        if (report.error() != null) {
            message.append(": ").append(report.error());
        }
        report.specs().stream()
                .filter(ApiCodegenSpecReport::failed)
                .forEach(spec -> message.append(System.lineSeparator())
                        .append("- ")
                        .append(spec.specId())
                        .append(": ")
                        .append(spec.error()));
        message.append(System.lineSeparator()).append("Report: ").append(report.reportMarkdown());
        return message.toString();
    }
}
