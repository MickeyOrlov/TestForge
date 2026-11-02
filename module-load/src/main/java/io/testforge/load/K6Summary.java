package io.testforge.load;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads the native legacy handleSummary object, explicitly selected by the runner's CLI flag. */
final class K6Summary {
    static final long MAX_BYTES = 8 * 1024 * 1024;

    private K6Summary() {
    }

    static LoadReport.Metrics read(Path file) throws IOException {
        return read(file, new LoadPolicyProperties(null, null, null));
    }

    static LoadReport.Metrics read(Path file, LoadPolicyProperties policy) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) == 0 || Files.size(file) > MAX_BYTES) {
            throw new LoadException("Missing, empty or oversized k6 summary; use the documented handleSummary hook");
        }
        JsonNode root = new ObjectMapper().readTree(file.toFile());
        if (root == null) {
            throw new LoadException("Empty k6 summary");
        }
        JsonNode metrics = root.path("metrics");
        if (!metrics.isObject()) {
            throw new LoadException("Unsupported k6 summary: metrics must be an object");
        }
        long iterations = count(metrics.path("iterations").path("values").path("count"));
        JsonNode http = metrics.path("http_reqs");
        long requests = http.isMissingNode() ? 0 : count(http.path("values").path("count"));
        double rate = http.isMissingNode() ? 0 : number(http.path("values").path("rate"));
        JsonNode droppedMetric = metrics.path("dropped_iterations");
        long dropped = droppedMetric.isMissingNode() ? 0 : count(droppedMetric.path("values").path("count"));
        int thresholds = 0;
        int failed = 0;
        for (JsonNode metric : metrics) {
            if (!metric.has("thresholds")) {
                continue;
            }
            JsonNode limits = metric.path("thresholds");
            if (!limits.isObject()) {
                throw new LoadException("Unsupported k6 summary: thresholds must be an object");
            }
            for (JsonNode limit : limits) {
                if (!limit.path("ok").isBoolean()) {
                    throw new LoadException("Unsupported k6 summary: threshold outcome must be boolean");
                }
                thresholds++;
                if (!limit.path("ok").booleanValue()) {
                    failed++;
                }
            }
        }
        if (iterations < 1 || policy.requireHttp() && requests < 1 || policy.requireThresholds() && thresholds == 0) {
            throw new LoadException("k6 summary does not satisfy the configured load validity policy");
        }
        Double p95 = optional(metrics.path("http_req_duration").path("values").path("p(95)"));
        Double errors = optional(metrics.path("http_req_failed").path("values").path("rate"));
        if (errors != null && errors > 1) {
            throw new LoadException("Unsupported k6 summary: HTTP error rate must be between 0 and 1");
        }
        return new LoadReport.Metrics(iterations, requests, rate, p95, errors, dropped, thresholds, failed);
    }

    private static long count(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 0) {
            throw new LoadException("Unsupported k6 summary: a count must be a non-negative integer");
        }
        return node.longValue();
    }

    private static double number(JsonNode node) {
        if (!node.isNumber() || !Double.isFinite(node.doubleValue()) || node.doubleValue() < 0) {
            throw new LoadException("Unsupported k6 summary: a metric must be a finite non-negative number");
        }
        return node.doubleValue();
    }

    private static Double optional(JsonNode node) {
        return node.isMissingNode() ? null : number(node);
    }
}
