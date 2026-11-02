package io.testforge.load;

/** Value-only evidence. No environment, raw output, request payloads or target URL are serialized. */
public record LoadReport(int formatVersion, String runId, Outcome outcome, String k6Version, String scriptSha256,
                         String startedAt, String finishedAt, Integer exitCode, String detail,
                         Metrics metrics, String directory, String dashboardUrl) {
    public LoadReport(int formatVersion, String runId, Outcome outcome, String k6Version, String scriptSha256,
                      String startedAt, String finishedAt, Integer exitCode, String detail, Metrics metrics,
                      String directory) {
        this(formatVersion, runId, outcome, k6Version, scriptSha256, startedAt, finishedAt, exitCode, detail,
                metrics, directory, null);
    }
    public enum Outcome { PASSED, THRESHOLDS_FAILED, EXECUTION_ERROR, TIMED_OUT, CANCELLED, INVALID_RESULT }

    /** Aggregates already calculated by k6; TestForge does not recompute timing statistics. */
    public record Metrics(long iterations, long requests, double requestsPerSecond, Double p95Millis,
                          Double errorRate, long droppedIterations, int thresholds, int failedThresholds) {
    }
}
