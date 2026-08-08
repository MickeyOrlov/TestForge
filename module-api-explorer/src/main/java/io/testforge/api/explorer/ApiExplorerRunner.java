package io.testforge.api.explorer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.testforge.api.discovery.ApiDiscoveryProperties;
import io.testforge.api.discovery.ApiSpecSource;
import io.testforge.api.discovery.OpenApiSpecParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Explores every configured document against a live environment and writes down
 * what it found.
 *
 * <p>The run is deliberately unshakeable: a document that will not parse, an
 * endpoint that refuses the connection, a response that is not the JSON it
 * claims to be — each of those becomes a recorded observation and the run keeps
 * going. A map of an API is only useful if it is complete, and the endpoints
 * most worth knowing about are exactly the ones that misbehave.
 */
public class ApiExplorerRunner {

    private static final Logger log = LoggerFactory.getLogger(ApiExplorerRunner.class);

    private final OpenApiSpecParser parser;
    private final OperationSelector selector;
    private final SafetyPolicy safety;
    private final RequestPlanner planner;
    private final ExchangeExecutor executor;
    private final ResponseContractChecker checker;
    private final ObservationFactory observations;
    private final ObjectMapper objectMapper;
    private final ApiDiscoveryProperties discoveryProperties;
    private final ApiExplorerProperties properties;

    public ApiExplorerRunner(
            OpenApiSpecParser parser,
            OperationSelector selector,
            SafetyPolicy safety,
            RequestPlanner planner,
            ExchangeExecutor executor,
            ResponseContractChecker checker,
            ObservationFactory observations,
            ObjectMapper objectMapper,
            ApiDiscoveryProperties discoveryProperties,
            ApiExplorerProperties properties) {
        this.parser = parser;
        this.selector = selector;
        this.safety = safety;
        this.planner = planner;
        this.executor = executor;
        this.checker = checker;
        this.observations = observations;
        this.objectMapper = objectMapper;
        this.discoveryProperties = discoveryProperties;
        this.properties = properties;
    }

    public ApiExplorerReport run() {
        Path outputDir = Path.of(properties.outputDir());
        Path reportJson = outputDir.resolve("report.json");
        Path reportMarkdown = outputDir.resolve("report.md");

        List<SpecExplorationReport> specReports = properties.enabled() ? exploreSpecs(outputDir) : List.of();
        boolean healthy = specReports.stream().noneMatch(SpecExplorationReport::failed);

        ApiExplorerReport report = new ApiExplorerReport(
                properties.enabled(),
                Instant.now().toString(),
                healthy,
                specReports,
                outputDir.toString(),
                reportJson.toString(),
                reportMarkdown.toString());

        writeJson(reportJson, report);
        writeString(reportMarkdown, ExplorerReportMarkdown.render(report));
        return report;
    }

    public ApiExplorerReport assertHealthy() {
        ApiExplorerReport report = run();
        if (!report.healthy()) {
            throw new ApiExplorerException(report);
        }
        return report;
    }

    private List<SpecExplorationReport> exploreSpecs(Path outputDir) {
        String baseUrl = resolveBaseUrl();
        List<SpecExplorationReport> reports = new ArrayList<>();
        for (ApiSpecSource source : sources()) {
            try {
                reports.add(exploreSpec(source, baseUrl, outputDir));
            } catch (RuntimeException e) {
                log.warn("Exploration of spec {} failed: {}", source.id(), e.toString());
                reports.add(SpecExplorationReport.broken(source.id(), source.location(), baseUrl, e.toString()));
            }
        }
        return List.copyOf(reports);
    }

    private String resolveBaseUrl() {
        String baseUrl = executor.baseUrl();
        // loud on purpose: an exploration run pointed at the wrong environment
        // should be visible in the console, not only in the artifact
        log.warn("API exploration will send {} requests to {}", properties.methods(), baseUrl);
        return baseUrl;
    }

    /** The spec registry belongs to module-api-discovery; this only filters it. */
    private List<ApiSpecSource> sources() {
        return discoveryProperties.specs().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .filter(entry -> properties.specs().isEmpty() || properties.specs().contains(entry.getKey()))
                .map(entry -> new ApiSpecSource(entry.getKey(), entry.getValue().location()))
                .toList();
    }

    private SpecExplorationReport exploreSpec(ApiSpecSource source, String baseUrl, Path outputDir) {
        OpenAPI openApi = parser.parse(source);
        String safeSpecId = safeName(source.id());
        Path observationsDir = outputDir.resolve(safeSpecId).resolve("observations");

        List<ObservationSummary> summaries = new ArrayList<>();
        Map<String, Integer> usedNames = new HashMap<>();
        int executed = 0;

        for (ExplorableOperation operation : selector.select(source.id(), openApi)) {
            ApiObservation observation = executed >= properties.maxOperations()
                    ? observations.skipped(operation, baseUrl,
                            PlannedRequest.skip(SkipReason.MAX_OPERATIONS_REACHED, null, List.of()))
                    : observe(operation, baseUrl);

            if (observation.outcome() != ExplorerOutcome.SKIPPED) {
                executed++;
            }

            Path artifact = observationsDir.resolve(artifactName(operation, usedNames) + ".json");
            writeJson(artifact, observation);
            summaries.add(ObservationSummary.of(observation, artifact.toString()));
        }

        return summarize(source, baseUrl, observationsDir, summaries);
    }

    /**
     * One operation, start to finish. Any failure here is data, not an
     * interruption — the surrounding loop must always get an observation back.
     */
    private ApiObservation observe(ExplorableOperation operation, String baseUrl) {
        Optional<SkipReason> refusal = safety.refuse(operation);
        if (refusal.isPresent()) {
            return observations.skipped(operation, baseUrl,
                    PlannedRequest.skip(refusal.get(), null, List.of()));
        }

        PlannedRequest plan = planner.plan(operation);
        if (!plan.sendable()) {
            return observations.skipped(operation, baseUrl, plan);
        }

        RuntimeExchange exchange = executor.execute(plan.request());
        List<ContractMismatch> mismatches = exchange.completed()
                ? checkQuietly(operation, exchange)
                : List.of();

        return observations.executed(operation, baseUrl, plan, exchange, mismatches);
    }

    /** A checker that throws must not turn a working endpoint into a failed run. */
    private List<ContractMismatch> checkQuietly(ExplorableOperation operation, RuntimeExchange exchange) {
        try {
            return checker.check(operation, exchange);
        } catch (RuntimeException e) {
            log.debug("Contract check for {} failed: {}", operation.key(), e.toString());
            return List.of();
        }
    }

    private SpecExplorationReport summarize(
            ApiSpecSource source, String baseUrl, Path observationsDir, List<ObservationSummary> summaries) {

        int passed = count(summaries, ExplorerOutcome.PASSED);
        int mismatched = count(summaries, ExplorerOutcome.CONTRACT_MISMATCH);
        int failed = count(summaries, ExplorerOutcome.FAILED);
        int skipped = count(summaries, ExplorerOutcome.SKIPPED);

        boolean unhealthy = (failed > 0 && properties.failOn().failure())
                || (mismatched > 0 && properties.failOn().contractMismatch());

        return new SpecExplorationReport(
                source.id(),
                source.location(),
                baseUrl,
                unhealthy,
                summaries.size(),
                passed,
                mismatched,
                failed,
                skipped,
                summaries,
                observationsDir.toString(),
                null);
    }

    private int count(List<ObservationSummary> summaries, ExplorerOutcome outcome) {
        return (int) summaries.stream().filter(summary -> summary.outcome() == outcome).count();
    }

    /**
     * Deterministic and collision-free: two operations whose ids sanitize to the
     * same stem get a stable numbered suffix in selection order.
     */
    private String artifactName(ExplorableOperation operation, Map<String, Integer> usedNames) {
        String base = safeName(operation.operationId());
        int seen = usedNames.merge(base, 1, Integer::sum);
        return seen == 1 ? base : base + "-" + seen;
    }

    private String safeName(String name) {
        String safe = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return safe.isBlank() ? "operation" : safe;
    }

    private void writeJson(Path path, Object value) {
        try {
            Files.createDirectories(path.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + path, e);
        }
    }

    private void writeString(Path path, String value) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, value, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + path, e);
        }
    }
}
