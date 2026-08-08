package io.testforge.api.fuzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.api.discovery.ApiDiscoveryProperties;
import io.testforge.api.discovery.OpenApiSpecParser;
import io.testforge.api.explorer.ApiExplorerProperties;
import io.testforge.api.explorer.ExchangeExecutor;
import io.testforge.api.explorer.ObservationFactory;
import io.testforge.api.explorer.OperationSelector;
import io.testforge.api.explorer.PreparedRequest;
import io.testforge.api.explorer.RequestPlanner;
import io.testforge.api.explorer.RequestValueResolver;
import io.testforge.api.explorer.ResponseContractChecker;
import io.testforge.api.explorer.RuntimeExchange;
import io.testforge.api.explorer.SafetyPolicy;
import io.testforge.api.explorer.SchemaValueFactory;
import io.testforge.http.Redactor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The runner end to end against a stubbed service that misbehaves in a specific,
 * documented way: it crashes on values longer than the declared maximum.
 */
class ApiFuzzRunnerTest {

    private static final String BASE_URL = "https://api.example.test";
    private static final String CRASHING_CASE = "getItem/path:itemId/TOO_LONG";

    @Test
    void findsTheCrashAndKeepsGoing(@TempDir Path output) {
        ApiFuzzReport report = run(output, List.of());

        assertThat(report.findings())
                .extracting(finding -> finding.fuzzCase().id())
                .contains(CRASHING_CASE);
        assertThat(report.findings())
                .extracting(FuzzObservation::verdict)
                .contains(FuzzVerdict.SERVER_ERROR);

        // the crash did not stop the operations after it
        assertThat(report.specs().getFirst().operations())
                .extracting(OperationFuzzReport::operationId)
                .contains("getItem", "search", "createItem");
    }

    @Test
    void writeOperationsAreNeverFuzzed(@TempDir Path output) {
        ApiFuzzReport report = run(output, List.of());

        assertThat(report.specs().getFirst().operations())
                .filteredOn(operation -> operation.operationId().equals("createItem"))
                .singleElement()
                .satisfies(operation -> {
                    assertThat(operation.cases()).isZero();
                    assertThat(operation.skipReason()).contains("method is not enabled");
                });
    }

    @Test
    void everyCaseIsAccountedForAndAttributableToOneParameter(@TempDir Path output) {
        ApiFuzzReport report = run(output, List.of());

        assertThat(report.specs().getFirst().cases()).isPositive();
        assertThat(report.specs().getFirst().operations())
                .flatExtracting(OperationFuzzReport::observations)
                .allSatisfy(observation -> {
                    assertThat(observation.fuzzCase().parameterName()).isNotBlank();
                    assertThat(observation.resolvedUrl()).startsWith(BASE_URL);
                });
    }

    @Test
    void aFindingCanBeReproducedFromItsCaseIdAlone(@TempDir Path output) {
        ApiFuzzReport report = run(output, List.of(CRASHING_CASE));

        assertThat(report.specs().getFirst().cases()).isEqualTo(1);
        assertThat(report.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.fuzzCase().id()).isEqualTo(CRASHING_CASE);
            assertThat(finding.verdict()).isEqualTo(FuzzVerdict.SERVER_ERROR);
        });

        // the other operations report why they contributed nothing
        assertThat(report.specs().getFirst().operations())
                .filteredOn(operation -> operation.operationId().equals("search"))
                .singleElement()
                .satisfies(operation -> assertThat(operation.skipReason())
                        .contains("no requested case belongs to this operation"));
    }

    @Test
    void artifactsAreDeterministicAcrossRuns(@TempDir Path output) throws IOException {
        run(output, List.of());
        String first = Files.readString(output.resolve("demo").resolve("getitem.json"));

        run(output, List.of());
        String second = Files.readString(output.resolve("demo").resolve("getitem.json"));

        assertThat(second).isEqualTo(first);
    }

    @Test
    void aServerErrorFailsTheRunAndTheMessageNamesTheCase(@TempDir Path output) {
        assertThatThrownBy(() -> runner(output, List.of()).assertHealthy())
                .isInstanceOf(ApiFuzzException.class)
                .hasMessageContaining("SERVER_ERROR")
                .hasMessageContaining(CRASHING_CASE)
                .hasMessageContaining("seed");
    }

    @Test
    void theReportLeadsWithFindingsAndPrintsHowToReplayThem(@TempDir Path output) throws IOException {
        ApiFuzzReport report = run(output, List.of());
        String markdown = Files.readString(Path.of(report.reportMarkdown()));

        assertThat(markdown)
                .contains("# API Fuzz Report")
                .contains("## Findings")
                .contains("### Reproduce a single case")
                .contains(CRASHING_CASE);
    }

    @Test
    void disabledFuzzingSendsNothingButStillWritesAReport(@TempDir Path output) {
        ApiFuzzReport report = runner(output, List.of(), false).run();

        assertThat(report.enabled()).isFalse();
        assertThat(report.specs()).isEmpty();
        assertThat(Path.of(report.reportJson())).exists();
    }

    private ApiFuzzReport run(Path output, List<String> onlyCases) {
        return runner(output, onlyCases).run();
    }

    private ApiFuzzRunner runner(Path output, List<String> onlyCases) {
        return runner(output, onlyCases, true);
    }

    private ApiFuzzRunner runner(Path output, List<String> onlyCases, boolean enabled) {
        ApiFuzzProperties properties = new ApiFuzzProperties(
                enabled, output.toString(), null, List.of(), 20260101L, null, null, null, null,
                null, 100, null, onlyCases, null, null);

        ObjectMapper objectMapper = new ObjectMapper();
        return new ApiFuzzRunner(
                new OpenApiSpecParser(),
                new OperationSelector(),
                new SafetyPolicy(properties.methods(), properties.allowUnsafeMethods(),
                        properties.includePaths(), properties.excludePaths()),
                new RequestPlanner(new RequestValueResolver(
                        new ApiExplorerProperties.ParameterProperties(Map.of("q", "query"), Map.of()),
                        new SchemaValueFactory()), true),
                new FuzzCaseGenerator(),
                new BodyCaseGenerator(objectMapper, new JsonBodyFactory(objectMapper)),
                new JsonBodyFactory(objectMapper),
                new JsonBodyMutator(objectMapper),
                new FuzzCaseSelector(properties.seed(), properties.maxCasesPerOperation()),
                executor(),
                new ResponseClassifier(new ResponseContractChecker(objectMapper), objectMapper),
                new ObservationFactory(new Redactor(objectMapper, List.of("authorization"), List.of("token")), 4000),
                objectMapper,
                new ApiDiscoveryProperties(true, null, null, null, null,
                        Map.of(FuzzFixtures.SPEC_ID, new ApiDiscoveryProperties.Spec(FuzzFixtures.LOCATION))),
                properties);
    }

    /**
     * A service that validates the lower bound properly and crashes on the
     * upper one — the shape of defect this module exists to find.
     */
    private ExchangeExecutor executor() {
        return new ExchangeExecutor() {
            @Override
            public RuntimeExchange execute(PreparedRequest request) {
                String itemId = request.pathParameters().get("itemId");
                if (itemId != null) {
                    if (itemId.length() > 8) {
                        return json(500, "{\"message\":\"internal error\"}");
                    }
                    return itemId.length() < 2
                            ? json(400, "{\"message\":\"too short\"}")
                            : json(200, "{\"id\":\"item-1\"}");
                }

                String q = request.queryParameters().get("q");
                return q == null || q.length() < 3
                        ? json(400, "{\"message\":\"q is required\"}")
                        : json(200, "{\"hits\":1}");
            }

            @Override
            public String baseUrl() {
                return BASE_URL;
            }

            private RuntimeExchange json(int status, String body) {
                return new RuntimeExchange(Map.of(), null, status, "application/json", Map.of(), body, 4L, null);
            }
        };
    }
}
