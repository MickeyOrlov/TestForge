package io.testforge.load;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.testforge.artifact.ArtifactSink;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Thin adapter over a local k6 process. Never schedules users, requests, retries or thresholds. */
public final class LoadRunner {
    private static final String SOURCE = "module-load";
    private static final Pattern VERSION = Pattern.compile("(?m)^k6 v(\\d+\\.\\d+\\.\\d+)(?:\\s|$)");
    private final LoadProperties properties;
    private final LoadProcess process;
    private final ArtifactSink sink;

    public LoadRunner(LoadProperties properties, LoadProcess process, ArtifactSink sink) {
        this.properties = properties;
        this.process = process;
        this.sink = sink == null ? ArtifactSink.NO_OP : sink;
    }

    /** Runs only when enabled. Execution failures are evidence, configuration errors throw before launch. */
    public LoadReport run() {
        properties.validateRun();
        Path script = Path.of(properties.script()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(script)) {
            throw new LoadException("forge.load.script must point at a readable local file");
        }
        String runId = UUID.randomUUID().toString();
        Path directory = Path.of(properties.outputDir()).resolve(runId);
        Path summary;
        Path html = directory.resolve("k6-report.html");
        String startedAt = Instant.now().toString();
        String version = null;
        String scriptHash;
        try {
            scriptHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(script)));
            Files.createDirectories(directory);
            // Ignore the user's ambient k6 config; native options remain in the scenario.
            Files.writeString(directory.resolve("k6-config.json"), "{}");
            // Keep native data outside artifact uploads even if the JVM is killed before cleanup.
            summary = Files.createTempDirectory("testforge-load-").resolve("summary.json");
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new LoadException("Could not prepare the load script or output directory");
        }
        LoadReport.Outcome outcome = LoadReport.Outcome.EXECUTION_ERROR;
        String detail = "k6 could not be started; check the configured executable";
        Integer exitCode = null;
        LoadReport.Metrics metrics = null;
        boolean interrupted = false;
        try {
            Map<String, String> environment = environment(runId, summary, html);
            LoadProcess.Result probe = process.run(List.of(properties.command(), "version"), script.getParent(),
                    environment, properties.timeout().compareTo(Duration.ofSeconds(5)) < 0
                            ? properties.timeout() : Duration.ofSeconds(5));
            Matcher recognized = VERSION.matcher(probe.output() == null ? "" : probe.output());
            if (!probe.timedOut() && probe.exitCode() == 0 && recognized.find()) {
                version = recognized.group(1);
            }
            if (probe.timedOut()) {
                outcome = LoadReport.Outcome.TIMED_OUT;
                detail = "k6 version probe timed out";
            } else if (!properties.expectedVersion().equals(version)) {
                detail = "k6 version does not match forge.load.expected-version";
            } else {
                List<String> command = new ArrayList<>(List.of(properties.command(), "run", "--no-usage-report",
                        "--new-machine-readable-summary=false", "--config",
                        directory.resolve("k6-config.json").toAbsolutePath().toString()));
                if (properties.prometheus().enabled()) {
                    command.addAll(List.of("--out", "experimental-prometheus-rw"));
                    // Native name defaults to the full URL too; omitting url alone is insufficient.
                    command.addAll(List.of("--system-tags", "proto,status,method,tls_version,scenario,expected_response,error_code"));
                }
                command.add(script.toString());
                LoadProcess.Result result = process.run(command, script.getParent(), environment, properties.timeout());
                exitCode = result.exitCode();
                if (result.timedOut()) {
                    outcome = LoadReport.Outcome.TIMED_OUT;
                    detail = "k6 exceeded forge.load.timeout; its process tree was stopped";
                } else if (exitCode != 0 && exitCode != 99) {
                    detail = "k6 did not complete normally; inspect the script/configuration (exit " + exitCode + ")";
                } else {
                    try {
                        metrics = K6Summary.read(summary, properties.policy());
                        if (metrics.failedThresholds() > 0) {
                            outcome = LoadReport.Outcome.THRESHOLDS_FAILED;
                            detail = "Native k6 thresholds failed" + (metrics.droppedIterations() > 0
                                    ? "; k6 also dropped iterations; the requested workload was not fully delivered" : "");
                        } else if (properties.policy().failOnDroppedIterations() && metrics.droppedIterations() > 0) {
                            outcome = LoadReport.Outcome.INVALID_RESULT;
                            detail = "k6 dropped iterations; the requested workload was not fully delivered";
                        } else if (exitCode == 99 && metrics.failedThresholds() == 0) {
                            outcome = LoadReport.Outcome.INVALID_RESULT;
                            detail = "k6 exit code and summary threshold outcomes disagree";
                        } else if (properties.htmlReport() && (!Files.isRegularFile(html) || Files.size(html) == 0)) {
                            outcome = LoadReport.Outcome.INVALID_RESULT;
                            detail = "k6 did not produce the requested HTML report";
                        } else {
                            outcome = LoadReport.Outcome.PASSED;
                            detail = metrics.thresholds() == 0 ? "k6 completed; no native thresholds configured"
                                    : "k6 completed and all native thresholds passed";
                        }
                    } catch (LoadException | IOException e) {
                        outcome = LoadReport.Outcome.INVALID_RESULT;
                        // A parse error can quote a secret-bearing value; never persist its message/cause.
                        detail = "Missing or invalid k6 summary; use the documented handleSummary contract";
                    }
                }
            }
        } catch (IOException e) {
            outcome = LoadReport.Outcome.EXECUTION_ERROR;
        } catch (InterruptedException e) {
            interrupted = true;
            outcome = LoadReport.Outcome.CANCELLED;
            detail = "Load execution was interrupted; its process tree was stopped";
        } finally {
            // Never let cleanup replace a verdict or prevent publishing evidence.
            if (!removePrivateSummary(summary.getParent())) {
                detail += "; private summary cleanup failed; remove the temporary testforge-load directory locally";
                if (outcome == LoadReport.Outcome.PASSED) outcome = LoadReport.Outcome.INVALID_RESULT;
            }
        }
        String finishedAt = Instant.now().toString();
        LoadReport report = new LoadReport(1, runId, outcome, version, scriptHash, startedAt,
                finishedAt, exitCode, detail, metrics, directory.toString(), dashboardUrl(runId, startedAt, finishedAt));
        try {
            write(report, directory, html);
            return report;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Runs, publishes evidence, then fails for every outcome other than PASSED. */
    public LoadReport assertHealthy() {
        LoadReport report = run();
        if (report.outcome() != LoadReport.Outcome.PASSED) {
            throw new LoadException("Load run " + report.outcome() + ": " + report.detail() + ". See "
                    + Path.of(report.directory()).resolve("report.md"));
        }
        return report;
    }

    private Map<String, String> environment(String runId, Path summary, Path html) {
        Map<String, String> environment = new LinkedHashMap<>(properties.environment());
        if (properties.baseUrl() != null && !properties.baseUrl().isBlank()) {
            environment.put("TESTFORGE_LOAD_BASE_URL", properties.baseUrl());
        }
        environment.put("TESTFORGE_LOAD_RUN_ID", runId);
        environment.put("TESTFORGE_LOAD_SUMMARY", summary.toAbsolutePath().toString());
        environment.put("K6_WEB_DASHBOARD", String.valueOf(properties.htmlReport()));
        environment.put("K6_WEB_DASHBOARD_PORT", "-1");
        environment.put("K6_WEB_DASHBOARD_PERIOD", "1s");
        environment.put("K6_WEB_DASHBOARD_OPEN", "false");
        environment.put("K6_WEB_DASHBOARD_EXPORT", properties.htmlReport() ? html.toAbsolutePath().toString() : "");
        environment.put("K6_NO_USAGE_REPORT", "true");
        PrometheusProperties prometheus = properties.prometheus();
        if (prometheus.enabled()) {
            environment.put("K6_PROMETHEUS_RW_SERVER_URL", prometheus.remoteWriteUrl());
            environment.put("K6_PROMETHEUS_RW_TREND_AS_NATIVE_HISTOGRAM", "true");
            environment.put("K6_PROMETHEUS_RW_STALE_MARKERS", "true");
            environment.put("K6_PROMETHEUS_RW_PUSH_INTERVAL", "1s");
            environment.put("K6_PROMETHEUS_RW_LABELS", "project=" + prometheus.project()
                    + ",environment=" + prometheus.environment()
                    + (prometheus.runIds() ? ",run_id=" + runId : "") + ",generator=local");
            if (prometheus.bearerToken() != null && !prometheus.bearerToken().isBlank()) {
                environment.put("K6_PROMETHEUS_RW_BEARER_TOKEN", prometheus.bearerToken());
            }
        }
        return environment;
    }

    private String dashboardUrl(String runId, String startedAt, String finishedAt) {
        PrometheusProperties prometheus = properties.prometheus();
        if (!prometheus.enabled()) {
            return null;
        }
        return prometheus.grafanaUrl().replaceAll("/+$", "") + "/d/testforge-load/testforge-load"
                + "?var-project=" + prometheus.project() + "&var-environment=" + prometheus.environment()
                + "&var-run_id=" + (prometheus.runIds() ? runId : "%24__all") + "&from=" + (Instant.parse(startedAt).toEpochMilli() - 1000)
                + "&to=" + (Instant.parse(finishedAt).toEpochMilli() + 1000);
    }

    static boolean removePrivateSummary(Path directory) {
        boolean removed = true;
        // walk does not follow symbolic links: never delete a script-created symlink's target.
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException | SecurityException e) {
                    removed = false;
                }
            }
        } catch (IOException | java.io.UncheckedIOException | SecurityException e) {
            removed = false;
        }
        return removed;
    }

    private void write(LoadReport report, Path directory, Path html) {
        Path json = directory.resolve("report.json");
        Path markdown = directory.resolve("report.md");
        try {
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(json.toFile(), report);
            StringBuilder text = new StringBuilder("# Load run — " + report.outcome() + "\n\n")
                    .append(report.detail()).append("\n\n- run: ").append(report.runId())
                    .append("\n- k6: ").append(report.k6Version())
                    .append("\n- script SHA-256: ").append(report.scriptSha256())
                    .append("\n- exit code: ").append(report.exitCode()).append('\n');
            if (report.dashboardUrl() != null) {
                text.append("- dashboard: ").append(report.dashboardUrl()).append('\n');
            }
            if (report.metrics() != null) {
                LoadReport.Metrics metrics = report.metrics();
                text.append("- iterations: ").append(metrics.iterations())
                        .append("\n- requests: ").append(metrics.requests())
                        .append("\n- actual requests/s: ").append(metrics.requestsPerSecond())
                        .append("\n- HTTP p95 ms: ").append(metrics.p95Millis())
                        .append("\n- HTTP error rate: ").append(metrics.errorRate())
                        .append("\n- dropped iterations: ").append(metrics.droppedIterations())
                        .append("\n- failed thresholds: ").append(metrics.failedThresholds())
                        .append(" / ").append(metrics.thresholds()).append('\n');
            }
            Files.writeString(markdown, text.toString());
        } catch (IOException e) {
            throw new LoadException("Could not write load run evidence");
        }
        Map<String, String> metadata = Map.of("outcome", report.outcome().name(), "runId", report.runId());
        sink.registerFile(SOURCE, "load", "report.json", json, "application/json", metadata);
        sink.registerFile(SOURCE, "load", "report.md", markdown, "text/markdown", metadata);
        if (properties.htmlReport() && Files.isRegularFile(html)) {
            sink.registerFile(SOURCE, "load", "k6-report.html", html, "text/html", metadata);
        }
    }
}
