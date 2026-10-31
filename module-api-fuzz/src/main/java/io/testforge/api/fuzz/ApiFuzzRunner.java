package io.testforge.api.fuzz;

import io.testforge.api.discovery.ApiDiscoveryProperties;
import io.testforge.artifact.ArtifactSink;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.Optional;
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
    private final ArtifactSink artifactSink;

    private static final String SOURCE = "module-api-fuzz";

    public ApiFuzzRunner(
            FuzzSpecMaterializer materializer,
            SchemathesisExecutor executor,
            NdjsonReportParser reportParser,
            FuzzEvidenceWriter evidenceWriter,
            ApiDiscoveryProperties discoveryProperties,
            ApiFuzzProperties properties) {
        this(materializer, executor, reportParser, evidenceWriter, discoveryProperties, properties, ArtifactSink.NO_OP);
    }

    public ApiFuzzRunner(
            FuzzSpecMaterializer materializer,
            SchemathesisExecutor executor,
            NdjsonReportParser reportParser,
            FuzzEvidenceWriter evidenceWriter,
            ApiDiscoveryProperties discoveryProperties,
            ApiFuzzProperties properties,
            ArtifactSink artifactSink) {
        this.materializer = Objects.requireNonNull(materializer, "materializer cannot be null");
        this.executor = Objects.requireNonNull(executor, "executor cannot be null");
        this.reportParser = Objects.requireNonNull(reportParser, "reportParser cannot be null");
        this.evidenceWriter = Objects.requireNonNull(evidenceWriter, "evidenceWriter cannot be null");
        this.discoveryProperties = discoveryProperties;
        this.properties = Objects.requireNonNull(properties, "properties cannot be null");
        this.artifactSink = artifactSink == null ? ArtifactSink.NO_OP : artifactSink;
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

        ApiFuzzProperties effectiveProperties = properties.withSeed(seed);

        String runId = UUID.randomUUID().toString();
        Instant startedAt = Instant.now();
        long startTimeMs = System.currentTimeMillis();

        Path outputDir = Path.of(effectiveProperties.outputDir());

        List<ApiFuzzFinding> aggregateFindings = new ArrayList<>();
        List<String> aggregateErrors = new ArrayList<>();
        Map<String, Path> aggregateArtifacts = new HashMap<>();
        int aggregateTotalScenarios = 0;
        int aggregateFailedScenarios = 0;

        ApiFuzzOutcome overallOutcome = ApiFuzzOutcome.PASSED;

        // Derived from properties alone, so it is identical for every spec.
        FuzzSafetyPolicy policy = FuzzSafetyPolicy.from(effectiveProperties);

        // Probed once per run, not once per spec: the executable cannot change
        // between specs, and a version probe is a whole extra process launch.
        String schemathesisVersionString;
        try {
            schemathesisVersionString = executor.probeVersion().semver();
        } catch (Exception e) {
            log.error("Failed to probe Schemathesis version: {}", e.getMessage());
            return failedRun(runId, seed, startedAt, startTimeMs,
                    "Failed to probe Schemathesis version: " + e.getMessage());
        }

        List<FuzzMarkdownReport.SpecSummary> summaries = new ArrayList<>();
        for (String specId : specIds) {
            Path specOutputDir = outputDir.resolve(specId);
            int errorsBefore = aggregateErrors.size();
            int scenariosBefore = aggregateTotalScenarios;
            int failedBefore = aggregateFailedScenarios;
            List<String> commandArgs = null;
            Map<String, String> specArtifacts = new HashMap<>();
            MaterializedSpec materializedSpec;
            try {
                materializedSpec = materializer.materialize(specId);
            } catch (Exception e) {
                log.error("Failed to materialize spec '{}': {}", specId, e.getMessage());
                aggregateErrors.add("Failed to materialize spec '" + specId + "': " + e.getMessage());
                overallOutcome = maxOutcome(overallOutcome, ApiFuzzOutcome.EXECUTION_ERROR);
                summaries.add(new FuzzMarkdownReport.SpecSummary(specId, ApiFuzzOutcome.EXECUTION_ERROR, 0, 0,
                        aggregateErrors.subList(errorsBefore, aggregateErrors.size()), null));
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

            // The config file is per-spec (it lands in the spec's own output
            // directory); the policy it encodes is not.
            Path generatedConfig = SchemathesisConfigFile.generate(specOutputDir, policy);
            aggregateArtifacts.put(specId + "/schemathesis.toml", generatedConfig);
            specArtifacts.put("schemathesis.toml", generatedConfig.toString());

            try {
                commandArgs = new SchemathesisCommand(effectiveProperties, policy, specPathOrUrl, generatedConfig).build();
            } catch (Exception e) {
                log.error("Failed to build Schemathesis command for spec '{}': {}", specId, e.getMessage());
                aggregateErrors.add("Failed to build Schemathesis command for spec '" + specId + "': " + e.getMessage());
                overallOutcome = maxOutcome(overallOutcome, ApiFuzzOutcome.CONFIGURATION_ERROR);
                summaries.add(new FuzzMarkdownReport.SpecSummary(specId, ApiFuzzOutcome.CONFIGURATION_ERROR, 0, 0,
                        aggregateErrors.subList(errorsBefore, aggregateErrors.size()), null));
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
                Path reportPath;
                try {
                    reportPath = findNdjsonReport(specOutputDir, outputDir);
                } catch (ApiFuzzException e) {
                    // Reading the directory failed. That is an execution problem
                    // with a real cause, not "no findings" and not "no report".
                    log.error("{}", e.getMessage());
                    aggregateErrors.add(e.getMessage());
                    reportPath = null;
                }
                if (reportPath == null || !Files.exists(reportPath)) {
                    log.error("NDJSON report file is missing for spec '{}'", specId);
                    aggregateErrors.add("NDJSON report file is missing for spec '" + specId + "'");
                    specOutcome = ApiFuzzOutcome.EXECUTION_ERROR;
                } else {
                    try {
                        ApiFuzzReport parsedReport = reportParser.parse(reportPath);
                        aggregateArtifacts.put(specId + "/report.ndjson", reportPath);
                        specArtifacts.put("report.ndjson", reportPath.toString());
                        aggregateTotalScenarios += parsedReport.totalScenarios();
                        aggregateFailedScenarios += parsedReport.failedScenarios();

                        if (parsedReport.findings() != null && !parsedReport.findings().isEmpty()) {
                            parsedReport.findings().forEach(finding -> aggregateFindings.add(finding.withSpec(specId)));
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
            summaries.add(new FuzzMarkdownReport.SpecSummary(specId, specOutcome,
                    aggregateTotalScenarios - scenariosBefore, aggregateFailedScenarios - failedBefore,
                    aggregateErrors.subList(errorsBefore, aggregateErrors.size()), commandArgs));


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
                    Map.copyOf(specArtifacts),
                    startedAt,
                    runDuration
            );

            try {
                evidenceWriter.writeEvidence(outputDir, evidence);
                Path runJsonPath = outputDir.resolve(runId).resolve(specId).resolve("run.json");
                aggregateArtifacts.put(specId + "/run.json", runJsonPath);
            } catch (Exception e) {
                log.warn("Failed to write fuzz run evidence for spec '{}': {}", specId, e.getMessage());
            }
        }

        Duration totalDuration = Duration.ofMillis(System.currentTimeMillis() - startTimeMs);
        String mainSpecId = specIds.size() == 1 ? specIds.get(0) : String.join(",", specIds);

        ApiFuzzReport report = new ApiFuzzReport(
                runId,
                mainSpecId,
                overallOutcome,
                schemathesisVersionString,
                seed,
                effectiveProperties.phases(),
                aggregateTotalScenarios,
                aggregateFailedScenarios,
                aggregateFindings,
                aggregateErrors,
                aggregateArtifacts,
                totalDuration,
                effectiveProperties.acceptedFindings()
        );
        try {
            aggregateArtifacts.put(FuzzMarkdownReport.FILE_NAME, FuzzMarkdownReport.write(outputDir, report, summaries));
            report = new ApiFuzzReport(report.runId(), report.specId(), report.outcome(), report.schemathesisVersion(),
                    report.seed(), report.phases(), report.totalScenarios(), report.failedScenarios(),
                    report.findings(), report.errors(), aggregateArtifacts, report.duration(),
                    report.acceptedFindings());
        } catch (ApiFuzzException e) {
            log.warn("{}", e.getMessage());
        }
        registerArtifacts(report);
        return report;
    }

    /**
     * Best-effort: every file the run produced (per-spec config, NDJSON report,
     * run evidence) becomes an entry of the unified run index, keyed as in
     * {@link ApiFuzzReport#artifacts()}.
     */
    private void registerArtifacts(ApiFuzzReport report) {
        Map<String, String> metadata = Map.of("outcome", report.outcome().name(), "runId", report.runId());
        new java.util.TreeMap<>(report.artifacts()).forEach((name, file) -> artifactSink.registerFile(
                SOURCE, "api-fuzz", name, file, mediaType(name), metadata));
    }

    private static String mediaType(String name) {
        if (name.endsWith(".json")) {
            return "application/json";
        }
        if (name.endsWith(".ndjson")) {
            return "application/x-ndjson";
        }
        if (name.endsWith(".md")) {
            return "text/markdown";
        }
        return "text/plain";
    }

    /**
     * A run that could not start at all. Distinct from a spec-level failure:
     * nothing was executed, so there is nothing to attribute to a spec.
     */
    private ApiFuzzReport failedRun(String runId, long seed, Instant startedAt, long startTimeMs, String error) {
        return new ApiFuzzReport(
                runId,
                "",
                ApiFuzzOutcome.EXECUTION_ERROR,
                "unknown",
                seed,
                properties.phases(),
                0,
                0,
                List.of(),
                List.of(error),
                Map.of(),
                Duration.ofMillis(System.currentTimeMillis() - startTimeMs));
    }

    /**
     * Findings do not fail the build by default. The first fuzz run against an
     * unfamiliar API finds plenty, and a red build on day one teaches a team to
     * switch the module off rather than to read the report — the same reasoning
     * {@code module-api-explorer} applies to contract mismatches. A run that
     * could not be executed is a different matter and always throws.
     *
     * <p>Set {@code forge.api-fuzz.fail-on-findings=true} once the first report
     * has been reviewed.
     */
    public ApiFuzzReport assertHealthy() {
        return assertHealthy(Boolean.TRUE.equals(properties.failOnFindings()));
    }

    /**
     * Applies the same verdict to a report already in hand. Without this, a
     * caller that wants to inspect a report before asserting on it has to call
     * {@link #run()} and then {@link #assertHealthy()}, which fuzzes the API a
     * second time.
     */
    public ApiFuzzReport assertHealthy(ApiFuzzReport report) {
        return check(report, Boolean.TRUE.equals(properties.failOnFindings()));
    }

    public ApiFuzzReport assertHealthy(boolean failOnFindings) {
        return check(run(), failOnFindings);
    }

    private ApiFuzzReport check(ApiFuzzReport report, boolean failOnFindings) {
        if (report.outcome() == ApiFuzzOutcome.EXECUTION_ERROR || report.outcome() == ApiFuzzOutcome.CONFIGURATION_ERROR) {
            throw new ApiFuzzException("API fuzzing failed with outcome " + report.outcome()
                    + (report.errors().isEmpty() ? "" : ": " + String.join("; ", report.errors())));
        }
        List<ApiFuzzFindingGroup> groups = report.newFindingGroups();
        if (failOnFindings && !groups.isEmpty()) {
            int cases = groups.stream().mapToInt(ApiFuzzFindingGroup::occurrences).sum();
            int accepted = report.findingGroups().size() - groups.size();
            throw new ApiFuzzException("API fuzzing findings: " + cases + (cases == 1 ? " failing case in " : " failing cases in ")
                    + groups.size() + (groups.size() == 1 ? " new check: " : " new checks: ") + groups
                    + (accepted == 0 ? "" : " (" + accepted + " accepted not counted)")
                    + (report.artifacts().containsKey(FuzzMarkdownReport.FILE_NAME)
                            ? "; see " + report.artifacts().get(FuzzMarkdownReport.FILE_NAME) : ""));
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

    /**
     * Locates the NDJSON report Schemathesis just wrote.
     *
     * <p>Scanning the directory is the normal path, not a fallback: Schemathesis
     * names reports with a timestamp ({@code ndjson-20260815T161302Z.ndjson}),
     * so the fixed {@code report.ndjson} name only appears when a test writes
     * it. Where several candidates exist — a directory reused across runs — the
     * newest wins, so a stale report is never parsed as this run's result.
     *
     * @throws ApiFuzzException if a directory cannot be read. Swallowing that
     *     produced the worst possible diagnostic: the caller reported a missing
     *     report while the real cause (permissions, an I/O error) went unsaid.
     */
    private Path findNdjsonReport(Path specOutputDir, Path outputDir) {
        for (Path dir : List.of(specOutputDir, outputDir)) {
            Path fixedName = dir.resolve("report.ndjson");
            if (Files.exists(fixedName)) {
                return fixedName;
            }
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (var stream = Files.list(dir)) {
                Optional<Path> newest = stream
                        .filter(p -> p.getFileName().toString().endsWith(".ndjson"))
                        .max(Comparator.comparing(ApiFuzzRunner::lastModifiedOrEpoch));
                if (newest.isPresent()) {
                    return newest.get();
                }
            } catch (IOException e) {
                throw new ApiFuzzException(
                        "Could not read the Schemathesis report directory " + dir + ": " + e.getMessage(), e);
            }
        }
        return null;
    }

    private static FileTime lastModifiedOrEpoch(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException e) {
            return FileTime.fromMillis(0L);
        }
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
