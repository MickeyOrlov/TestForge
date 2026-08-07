package io.testforge.api.discovery;

public class ApiDiscoveryException extends AssertionError {

    private final ApiDiscoveryReport report;

    public ApiDiscoveryException(ApiDiscoveryReport report) {
        super("API discovery drift found. Report: " + report.reportMarkdown());
        this.report = report;
    }

    public ApiDiscoveryReport report() {
        return report;
    }
}
