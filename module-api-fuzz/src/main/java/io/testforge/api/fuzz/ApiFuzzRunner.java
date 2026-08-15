package io.testforge.api.fuzz;

import io.testforge.api.discovery.ApiDiscoveryProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrator for running external Schemathesis CLI fuzzing against OpenAPI specifications.
 */
public class ApiFuzzRunner {

    private static final Logger log = LoggerFactory.getLogger(ApiFuzzRunner.class);

    private final FuzzSpecMaterializer materializer;
    private final SchemathesisExecutor executor;
    private final NdjsonReportParser reportParser;
    private final FuzzEvidenceWriter evidenceWriter;
    private final ApiDiscoveryProperties discoveryProperties;
    private final ApiFuzzProperties properties;

    public ApiFuzzRunner(
            FuzzSpecMaterializer materializer,
            SchemathesisExecutor executor,
            NdjsonReportParser reportParser,
            FuzzEvidenceWriter evidenceWriter,
            ApiDiscoveryProperties discoveryProperties,
            ApiFuzzProperties properties) {
        this.materializer = Objects.requireNonNull(materializer, "materializer cannot be null");
        this.executor = Objects.requireNonNull(executor, "executor cannot be null");
        this.reportParser = Objects.requireNonNull(reportParser, "reportParser cannot be null");
        this.evidenceWriter = Objects.requireNonNull(evidenceWriter, "evidenceWriter cannot be null");
        this.discoveryProperties = discoveryProperties;
        this.properties = Objects.requireNonNull(properties, "properties cannot be null");
    }

    public ApiFuzzReport run() {
        if (!Boolean.TRUE.equals(properties.enabled())) {
            log.info("API fuzzing is disabled (forge.api-fuzz.enabled=false). Skipping run.");
            return skippedReport();
        }

        List<String> specIds = resolveSpecIds();
        if (specIds.isEmpty()) {
            log.info("No API specs configured for fuzzing. Skipping run.");
            return skippedReport();
        }

        long seed = properties.seed() != null
                ? properties.seed()
                : Math.abs(ThreadLocalRandom.current().nextLong(1L, Long.MAX_VALUE));

        ApiFuzzProperties effectiveProperties = withSeed(properties, seed);

        String runId = UUID.randomUUID().toString();
        Instant startedAt = Instant.now();
        long startTimeMs = System.currentTimeMillis();

        Path outputDir = Path.of(effectiveProperties.outputDir());

        List<ApiFuzzFinding> aggregateFindings = new ArrayList<>();
        List<String> aggregateErrors = new ArrayList<>();
        Map<String, Path> aggregateArtifacts = new HashMap<>();

        ApiFuzzOutcome overallOutcome = ApiFuzzOutcome.PASSED;
        String schemathesisVersionString = null;

        for (String specId : specIds) {
            Path specOutputDir = outputDir.resolve(specId);
            MaterializedSpec materializedSpec;
            try {
                materializedSpec = materializer.materialize(specId);
            } catch (Exception e) {
                log.error("Failed to materialize spec '{}': {}", specId, e.getMessage());
                aggregateErrors.add("Failed to materialize spec '" + specId + "': " + e.getMessage());
                overallOutcome = maxOutcome(overallOutcome, ApiFuzzOutcome.EXECUTION_ERROR);
                continue;
            }

            String specLocation;
            String specPathOrUrl;
            if (materializedSpec instanceof MaterializedSpec.LocalFile localFile) {
                specLocation = localFile.path().toString();
                specPathOrUrl = localFile.path().toString();
            } else if (materializedSpec instanceof MaterializedSpec.RemoteUrl remoteUrl) {
                specLocation = remoteUrl.url();
                specPathOrUrl = remoteUrl.url();
            } else {
                specLocation = specId;
                specPathOrUrl = specId;
            }

            FuzzSafetyPolicy policy = FuzzSafetyPolicy.from(effectiveProperties);
            Path generatedConfig = SchemathesisConfigFile.generate(specOutputDir, policy);
            aggregateArtifacts.put(specId + "/schemathesis.toml", generatedConfig);

            List<String> commandArgs;
            try {
                commandArgs = new SchemathesisCommand(effectiveProperties, policy, specPathOrUrl, generatedConfig).build();
            } catch (Exception e) {
                log.error("Failed to build Schemathesis command for spec '{}': {}", specId, e.getMessage());
                aggregateErrors.add("Failed to build Schemathesis command for spec '" + specId + "': " + e.getMessage());
                overallOutcome = maxOutcome(overallOutcome, ApiFuzzOutcome.CONFIGURATION_ERROR);
                continue;
            }

            SchemathesisVersion version;
            try {
                version = executor.probeVersion();
                schemathesisVersionString = version.semver();
            } catch (Exception e) {
                log.error("Failed to probe Schemathesis version: {}", e.getMessage());
                aggregateErrors.add("Failed to probe Schemathesis version: " + e.getMessage());
                overallOutcome = maxOutcome(overallOutcome, ApiFuzzOutcome.EXECUTION_ERROR);
                continue;
            }

            Duration timeout = Duration.ofSeconds(effectiveProperties.timeoutSeconds());
            List<String> argsForExecutor = commandArgs.subList(1, commandArgs.size());

            ProcessResult result = executor.run(argsForExecutor, specOutputDir, timeout);

            int exitCode = result.exitCode();
            ApiFuzzOutcome specOutcome = ApiFuzzOutcome.PASSED;

            if (result.timedOut()) {
                log.error("Schemathesis execution timed out for spec '{}'", specId);
                aggregateErrors.add("Schemathesis execution timed out after " + effectiveProperties.timeoutSeconds() + " seconds");
                specOutcome = ApiFuzzOutcome.EXECUTION_ERROR;
            } else if (exitCode == 2) {
                log.error("Schemathesis configuration error (exit code 2) for spec '{}': {}", specId, result.stderr());
                aggregateErrors.add("Schemathesis configuration error (exit code 2): " + result.stderr());
                specOutcome = ApiFuzzOutcome.CONFIGURATION_ERROR;
            } else {
                Path reportPath = findNdjsonReport(specOutputDir, outputDir);
                if (reportPath == null || !Files.exists(reportPath)) {
                    log.error("NDJSON report file is missing for spec '{}'", specId);
                    aggregateErrors.add("NDJSON report file is missing for spec '" + specId + "'");
                    specOutcome = ApiFuzzOutcome.EXECUTION_ERROR;
                } else {
                    try {
                        ApiFuzzReport parsedReport = reportParser.parse(reportPath);
                        aggregateArtifacts.put(specId + "/report.ndjson", reportPath);

                        if (parsedReport.findings() != null && !parsedReport.findings().isEmpty()) {
                            aggregateFindings.addAll(parsedReport.findings());
                            specOutcome = ApiFuzzOutcome.FINDINGS;
                        } else if (parsedReport.outcome() == ApiFuzzOutcome.EXECUTION_ERROR) {
                            specOutcome = ApiFuzzOutcome.EXECUTION_ERROR;
                            if (parsedReport.errors() != null) {
                                aggregateErrors.addAll(parsedReport.errors());
                            }
                        } else if (exitCode != 0) {
                            log.error("Schemathesis process exited with code {} for spec '{}'", exitCode, specId);
                            aggregateErrors.add("Schemathesis process exited with code " + exitCode + ": " + result.stderr());
                            specOutcome = ApiFuzzOutcome.EXECUTION_ERROR;
                        } else {
                            specOutcome = ApiFuzzOutcome.PASSED;
                        }
                    } catch (Exception e) {
                        log.error("Failed to parse NDJSON report for spec '{}': {}", specId, e.getMessage());
                        aggregateErrors.add("Failed to parse NDJSON report for spec '" + specId + "': " + e.getMessage());
                        specOutcome = ApiFuzzOutcome.EXECUTION_ERROR;
                    }
                }
            }

            overallOutcome = maxOutcome(overallOutcome, specOutcome);

            Map<String, String> evidenceArtifacts = new HashMap<>();
            aggregateArtifacts.forEach((k, v) -> evidenceArtifacts.put(k, v.toString()));

            Duration runDuration = Duration.ofMillis(System.currentTimeMillis() - startTimeMs);
            FuzzRunEvidence evidence = new FuzzRunEvidence(
                    runId,
                    specId,
                    specLocation,
                    effectiveProperties.baseUrl(),
                    schemathesisVersionString != null ? schemathesisVersionString : "unknown",
                    seed,
                    effectiveProperties.phases(),
                    policy.permittedMethods(),
                    effectiveProperties.generationMode(),
                    effectiveProperties.maxExamples(),
                    effectiveProperties.allowUnsafeMethods(),
                    exitCode,
                    specOutcome.name(),
                    evidenceArtifacts,
                    startedAt,
                    runDuration
            );

            try {
                evidenceWriter.writeEvidence(outputDir, evidence);
                Path runJsonPath = outputDir.resolve(runId).resolve("run.json");
                aggregateArtifacts.put(specId + "/run.json", runJsonPath);
            } catch (Exception e) {
                log.warn("Failed to write fuzz run evidence for spec '{}': {}", specId, e.getMessage());
            }
        }

        Duration totalDuration = Duration.ofMillis(System.currentTimeMillis() - startTimeMs);
        String mainSpecId = specIds.size() == 1 ? specIds.get(0) : String.join(",", specIds);

        return new ApiFuzzReport(
                runId,
                mainSpecId,
                overallOutcome,
                schemathesisVersionString,
                seed,
                effectiveProperties.phases(),
                0,
                aggregateFindings.size(),
                aggregateFindings,
                aggregateErrors,
                aggregateArtifacts,
                totalDuration
        );
    }

    public ApiFuzzReport assertHealthy() {
        return assertHealthy(false);
    }

    public ApiFuzzReport assertHealthy(boolean failOnFindings) {
        ApiFuzzReport report = run();
        if (report.outcome() == ApiFuzzOutcome.EXECUTION_ERROR || report.outcome() == ApiFuzzOutcome.CONFIGURATION_ERROR) {
            throw new ApiFuzzException("API fuzzing failed with outcome " + report.outcome()
                    + (report.errors().isEmpty() ? "" : ": " + String.join("; ", report.errors())));
        }
        if (failOnFindings && report.hasFindings()) {
            throw new ApiFuzzException("API fuzzing discovered " + report.findings().size() + " findings: " + report.findings());
        }
        return report;
    }

    private List<String> resolveSpecIds() {
        if (!properties.specs().isEmpty()) {
            return properties.specs();
        }
        if (discoveryProperties != null && discoveryProperties.specs() != null) {
            return discoveryProperties.specs().keySet().stream().sorted().toList();
        }
        return List.of();
    }

    private Path findNdjsonReport(Path specOutputDir, Path outputDir) {
        Path reportInSpec = specOutputDir.resolve("report.ndjson");
        if (Files.exists(reportInSpec)) {
            return reportInSpec;
        }
        Path reportInOutput = outputDir.resolve("report.ndjson");
        if (Files.exists(reportInOutput)) {
            return reportInOutput;
        }
        if (Files.exists(specOutputDir)) {
            try (var stream = Files.list(specOutputDir)) {
                var found = stream.filter(p -> p.toString().endsWith(".ndjson")).findFirst();
                if (found.isPresent()) {
                    return found.get();
                }
            } catch (IOException ignored) {
            }
        }
        if (Files.exists(outputDir)) {
            try (var stream = Files.list(outputDir)) {
                var found = stream.filter(p -> p.toString().endsWith(".ndjson")).findFirst();
                if (found.isPresent()) {
                    return found.get();
                }
            } catch (IOException ignored) {
            }
        }
        return reportInSpec;
    }

    private ApiFuzzOutcome maxOutcome(ApiFuzzOutcome o1, ApiFuzzOutcome o2) {
        if (o1 == ApiFuzzOutcome.CONFIGURATION_ERROR || o2 == ApiFuzzOutcome.CONFIGURATION_ERROR) {
            return ApiFuzzOutcome.CONFIGURATION_ERROR;
        }
        if (o1 == ApiFuzzOutcome.EXECUTION_ERROR || o2 == ApiFuzzOutcome.EXECUTION_ERROR) {
            return ApiFuzzOutcome.EXECUTION_ERROR;
        }
        if (o1 == ApiFuzzOutcome.FINDINGS || o2 == ApiFuzzOutcome.FINDINGS) {
            return ApiFuzzOutcome.FINDINGS;
        }
        return ApiFuzzOutcome.PASSED;
    }

    private ApiFuzzProperties withSeed(ApiFuzzProperties props, long seed) {
        return new ApiFuzzProperties(
                props.enabled(),
                props.outputDir(),
                props.specs(),
                props.baseUrl(),
                props.methods(),
                props.allowUnsafeMethods(),
                props.phases(),
                seed,
                props.maxExamples(),
                props.generationMode(),
                props.maxFailures(),
                props.timeoutSeconds(),
                props.command(),
                props.configFile()
        );
    }

    private ApiFuzzReport skippedReport() {
        return new ApiFuzzReport(
                UUID.randomUUID().toString(),
                null,
                ApiFuzzOutcome.PASSED,
                null,
                null,
                List.of(),
                0,
                0,
                List.of(),
                List.of(),
                Map.of(),
                Duration.ZERO
        );
    }
}
