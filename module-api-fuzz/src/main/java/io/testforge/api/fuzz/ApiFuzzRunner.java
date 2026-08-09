package io.testforge.api.fuzz;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.testforge.api.discovery.ApiDiscoveryProperties;
import io.testforge.api.discovery.ApiSpecSource;
import io.testforge.api.discovery.OpenApiSpecParser;
import com.fasterxml.jackson.databind.JsonNode;
import io.testforge.api.explorer.ExchangeExecutor;
import io.testforge.api.explorer.ExplorableOperation;
import io.testforge.api.explorer.ObservationFactory;
import io.testforge.api.explorer.OperationSelector;
import io.testforge.api.explorer.PlannedRequest;
import io.testforge.api.explorer.PreparedRequest;
import io.testforge.api.explorer.RequestPlanner;
import io.testforge.api.explorer.RuntimeExchange;
import io.testforge.api.explorer.SafetyPolicy;
import io.testforge.api.fuzz.ApiFuzzReport.SpecFuzzReport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends deliberately wrong values at an API and reports where the answers stop
 * matching the document.
 *
 * <p>Built on the explorer rather than beside it: the same operation model, the
 * same safety gate, the same value resolution for everything the case is not
 * mutating, the same executor, the same contract checker. What this module adds
 * is the case matrix and the judgement of the response — everything else would
 * have been a second copy of something that already works.
 *
 * <p>The run is bounded on purpose. Fuzzing is the one thing in this repository
 * that deliberately sends bad data at somebody's environment, so it is off by
 * default, safe methods only unless two keys are turned, capped per operation
 * and in total, and sequential.
 */
public class ApiFuzzRunner {

    private static final Logger log = LoggerFactory.getLogger(ApiFuzzRunner.class);

    private final OpenApiSpecParser parser;
    private final OperationSelector selector;
    private final SafetyPolicy safety;
    private final RequestPlanner planner;
    private final FuzzCaseGenerator generator;
    private final BodyCaseGenerator bodyCases;
    private final JsonBodyFactory bodyFactory;
    private final JsonBodyMutator bodyMutator;
    private final FuzzCaseSelector cases;
    private final ExchangeExecutor executor;
    private final ResponseClassifier classifier;
    private final ObservationFactory redaction;
    private final ObjectMapper objectMapper;
    private final ApiDiscoveryProperties discoveryProperties;
    private final ApiFuzzProperties properties;

    public ApiFuzzRunner(
            OpenApiSpecParser parser,
            OperationSelector selector,
            SafetyPolicy safety,
            RequestPlanner planner,
            FuzzCaseGenerator generator,
            BodyCaseGenerator bodyCases,
            JsonBodyFactory bodyFactory,
            JsonBodyMutator bodyMutator,
            FuzzCaseSelector cases,
            ExchangeExecutor executor,
            ResponseClassifier classifier,
            ObservationFactory redaction,
            ObjectMapper objectMapper,
            ApiDiscoveryProperties discoveryProperties,
            ApiFuzzProperties properties) {
        this.parser = parser;
        this.selector = selector;
        this.safety = safety;
        this.planner = planner;
        this.generator = generator;
        this.bodyCases = bodyCases;
        this.bodyFactory = bodyFactory;
        this.bodyMutator = bodyMutator;
        this.cases = cases;
        this.executor = executor;
        this.classifier = classifier;
        this.redaction = redaction;
        this.objectMapper = objectMapper;
        this.discoveryProperties = discoveryProperties;
        this.properties = properties;
    }

    public ApiFuzzReport run() {
        Path outputDir = Path.of(properties.outputDir());
        Path reportJson = outputDir.resolve("report.json");
        Path reportMarkdown = outputDir.resolve("report.md");

        List<SpecFuzzReport> specs = properties.enabled() ? fuzzSpecs(outputDir) : List.of();
        boolean healthy = specs.stream().noneMatch(SpecFuzzReport::failed);

        ApiFuzzReport report = new ApiFuzzReport(
                properties.enabled(),
                Instant.now().toString(),
                healthy,
                properties.seed(),
                specs,
                outputDir.toString(),
                reportJson.toString(),
                reportMarkdown.toString());

        writeJson(reportJson, report);
        writeString(reportMarkdown, FuzzReportMarkdown.render(report));
        return report;
    }

    public ApiFuzzReport assertHealthy() {
        ApiFuzzReport report = run();
        if (!report.healthy()) {
            throw new ApiFuzzException(report);
        }
        return report;
    }

    private List<SpecFuzzReport> fuzzSpecs(Path outputDir) {
        String baseUrl = executor.baseUrl();
        log.warn("API fuzzing will send malformed {} requests to {} (seed {})",
                properties.methods(), baseUrl, properties.seed());

        List<SpecFuzzReport> reports = new ArrayList<>();
        for (ApiSpecSource source : sources()) {
            try {
                reports.add(fuzzSpec(source, baseUrl, outputDir));
            } catch (RuntimeException e) {
                log.warn("Fuzzing spec {} failed: {}", source.id(), e.toString());
                reports.add(SpecFuzzReport.broken(source.id(), source.location(), baseUrl, e.toString()));
            }
        }
        return List.copyOf(reports);
    }

    private List<ApiSpecSource> sources() {
        return discoveryProperties.specs().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .filter(entry -> properties.specs().isEmpty() || properties.specs().contains(entry.getKey()))
                .map(entry -> new ApiSpecSource(entry.getKey(), entry.getValue().location()))
                .toList();
    }

    private SpecFuzzReport fuzzSpec(ApiSpecSource source, String baseUrl, Path outputDir) {
        OpenAPI openApi = parser.parse(source);
        String safeSpecId = safeName(source.id());
        Path specDir = outputDir.resolve(safeSpecId);

        List<OperationFuzzReport> operations = new ArrayList<>();
        int fuzzed = 0;

        for (ExplorableOperation operation : selector.select(source.id(), openApi)) {
            if (fuzzed >= properties.maxOperations()) {
                operations.add(OperationFuzzReport.skipped(operation.operationId(), operation.key(),
                        "forge.api-fuzz.max-operations reached"));
                continue;
            }

            OperationFuzzReport report = fuzzOperation(operation, baseUrl, specDir);
            operations.add(report);
            if (report.cases() > 0) {
                fuzzed++;
            }
        }

        int totalCases = operations.stream().mapToInt(OperationFuzzReport::cases).sum();
        int findings = operations.stream().mapToInt(OperationFuzzReport::findings).sum();
        boolean failed = operations.stream()
                .flatMap(operation -> operation.observations().stream())
                .anyMatch(observation -> properties.failOn().fails(observation.verdict()));

        return new SpecFuzzReport(source.id(), source.location(), baseUrl, failed,
                operations.size(), totalCases, findings, operations, null);
    }

    private OperationFuzzReport fuzzOperation(ExplorableOperation operation, String baseUrl, Path specDir) {
        var refusal = safety.refuse(operation);
        if (refusal.isPresent()) {
            return OperationFuzzReport.skipped(operation.operationId(), operation.key(),
                    refusal.get().description());
        }

        // the baseline gives every parameter the case is not mutating a value
        // the service would normally accept, so a finding is about one field
        PlannedRequest baseline = planner.plan(operation);
        if (!baseline.sendable()) {
            return OperationFuzzReport.skipped(operation.operationId(), operation.key(),
                    baseline.skipReason().description());
        }

        BodyPlan bodyPlan = BodyPlan.from(operation.operation(), bodyFactory);
        if (bodyPlan.blocked()) {
            // a request the schema would reject anyway makes every verdict
            // meaningless, so nothing is sent
            return OperationFuzzReport.skipped(operation.operationId(), operation.key(),
                    "no valid request body could be built: " + bodyPlan.unsupportedReason());
        }

        List<FuzzCase> generated = new java.util.ArrayList<>(generator.generate(operation));
        if (bodyPlan.usable()) {
            generated.addAll(bodyCases.generate(operation, bodyPlan.schema(), bodyPlan.unfuzzablePaths()));
        }
        List<FuzzCase> selected = properties.replaying()
                ? cases.only(generated, properties.onlyCases())
                : cases.select(generated);

        if (selected.isEmpty()) {
            return OperationFuzzReport.skipped(operation.operationId(), operation.key(),
                    properties.replaying() ? "no requested case belongs to this operation"
                            : "the document declares nothing to vary");
        }

        List<FuzzObservation> observations = new ArrayList<>();
        for (FuzzCase fuzzCase : selected) {
            observations.add(execute(operation, baseline.request(), bodyPlan, fuzzCase, baseUrl));
        }

        Path artifact = specDir.resolve(safeName(operation.operationId()) + ".json");
        writeJson(artifact, observations);

        int findings = (int) observations.stream().filter(FuzzObservation::finding).count();
        return new OperationFuzzReport(operation.operationId(), operation.key(),
                observations.size(), findings, observations, artifact.toString(), null);
    }

    /** One case. Anything that goes wrong here is a verdict, never an interruption. */
    private FuzzObservation execute(ExplorableOperation operation, PreparedRequest baseline,
                                    BodyPlan bodyPlan, FuzzCase fuzzCase, String baseUrl) {
        PreparedRequest request = mutate(baseline, bodyPlan, fuzzCase);
        if (request == null) {
            return FuzzObservation.notApplicable(redact(fuzzCase), baseUrl + baseline.resolvedTarget());
        }
        RuntimeExchange exchange;
        try {
            exchange = executor.execute(request);
        } catch (RuntimeException e) {
            exchange = RuntimeExchange.failed(Map.of(), e.toString(), 0L);
        }

        ResponseClassifier.Classification classification = classifier.classify(operation, fuzzCase, exchange);

        return new FuzzObservation(
                redact(fuzzCase),
                baseUrl + request.resolvedTarget(),
                classification.verdict(),
                fuzzCase.expectation(),
                exchange.completed() ? exchange.status() : null,
                exchange.contentType(),
                redaction.redactBody(exchange.responseBody()),
                exchange.durationMillis(),
                classification.inputReflected(),
                classification.mismatches(),
                exchange.completed() ? null : exchange.error(),
                fragment(fuzzCase));
    }

    /** The smallest thing a reader needs to see what was sent, already redacted. */
    private String fragment(FuzzCase fuzzCase) {
        String value = redaction.redactParameterValue(fuzzCase.parameterName(), fuzzCase.value());
        if (fuzzCase.omitted()) {
            return fuzzCase.parameterName() + " <omitted>";
        }
        return fuzzCase.parameterName() + " = " + redaction.redactBody(value);
    }

    /**
     * Applies the one thing this case changes. Everything else — including the
     * valid body, when the operation has one — stays exactly as the baseline
     * built it, which is what makes a finding attributable to a single field.
     *
     * <p>Null when the case does not apply to this baseline, for instance a
     * body path that a chosen {@code oneOf} branch does not contain.
     */
    private PreparedRequest mutate(PreparedRequest baseline, BodyPlan bodyPlan, FuzzCase fuzzCase) {
        Map<String, String> pathParameters = new LinkedHashMap<>(baseline.pathParameters());
        Map<String, String> queryParameters = new LinkedHashMap<>(baseline.queryParameters());
        String body = bodyPlan.usable() ? bodyPlan.baseline().toString() : null;

        if (fuzzCase.bodyCase()) {
            Optional<String> mutated = bodyMutator.apply(bodyPlan.baseline(), fuzzCase);
            if (mutated.isEmpty()) {
                return null;
            }
            body = mutated.get();
        } else if (fuzzCase.omitted()) {
            queryParameters.remove(fuzzCase.parameterName());
        } else if ("path".equals(fuzzCase.in())) {
            pathParameters.put(fuzzCase.parameterName(), fuzzCase.value());
        } else {
            queryParameters.put(fuzzCase.parameterName(), fuzzCase.value());
        }

        return new PreparedRequest(baseline.method(), baseline.pathTemplate(), pathParameters, queryParameters,
                body, body == null ? null : bodyPlan.contentType());
    }

    /** A case built on a credential-named parameter must not print the credential. */
    private FuzzCase redact(FuzzCase fuzzCase) {
        String value = redaction.redactParameterValue(fuzzCase.parameterName(), fuzzCase.value());
        if (value == null ? fuzzCase.value() == null : value.equals(fuzzCase.value())) {
            return fuzzCase;
        }
        return new FuzzCase(fuzzCase.id(), fuzzCase.specId(), fuzzCase.operationId(), fuzzCase.operationKey(),
                fuzzCase.parameterName(), fuzzCase.in(), fuzzCase.kind(), fuzzCase.expectation(),
                value, fuzzCase.omitted());
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
