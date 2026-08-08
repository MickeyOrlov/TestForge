package io.testforge.api.explorer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.api.discovery.ApiDiscoveryProperties;
import io.testforge.api.discovery.OpenApiSpecParser;
import io.testforge.http.Redactor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The runner end to end, with a stubbed executor instead of a server — which is
 * the point of {@link ExchangeExecutor} being an interface.
 */
class ApiExplorerRunnerTest {

    private static final String BASE_URL = "https://api.example.test";

    @Test
    void producesAnOutcomeForEveryOperationAndSurvivesAFailingOne(@TempDir Path output) {
        ApiExplorerReport report = run(output, executor());

        SpecExplorationReport spec = report.specs().getFirst();
        assertThat(spec.operations()).isEqualTo(5);
        assertThat(spec.passed()).isEqualTo(1);
        assertThat(spec.contractMismatch()).isEqualTo(1);
        assertThat(spec.failedCalls()).isEqualTo(1);
        assertThat(spec.skipped()).isEqualTo(2);

        // the endpoint that refused the connection did not stop the two after it
        assertThat(spec.observations())
                .extracting(ObservationSummary::key)
                .contains("GET /tasks", "GET /tasks/{taskId}", "GET /reports",
                        "POST /tasks", "DELETE /tasks/{taskId}");
    }

    @Test
    void writeMethodsAreRecordedAsSkippedWithTheirReason(@TempDir Path output) {
        ApiExplorerReport report = run(output, executor());

        assertThat(report.specs().getFirst().observations())
                .filteredOn(observation -> observation.key().equals("DELETE /tasks/{taskId}"))
                .singleElement()
                .satisfies(observation -> {
                    assertThat(observation.outcome()).isEqualTo(ExplorerOutcome.SKIPPED);
                    assertThat(observation.skipReason()).isEqualTo(SkipReason.METHOD_NOT_ENABLED);
                });
    }

    @Test
    void oneObservationFilePerOperationWithDeterministicNames(@TempDir Path output) throws IOException {
        run(output, executor());
        List<String> first = observationFiles(output);

        run(output, executor());
        List<String> second = observationFiles(output);

        assertThat(first).containsExactly(
                "createtask.json", "deletetask.json", "gettask.json", "listreports.json", "listtasks.json");
        assertThat(second).isEqualTo(first);
    }

    @Test
    void secretsNeverReachAnObservationFile(@TempDir Path output) throws IOException {
        run(output, executor());

        String observation = Files.readString(
                output.resolve("demo").resolve("observations").resolve("listtasks.json"));

        assertThat(observation)
                .doesNotContain("Bearer super-secret")
                .contains("***");
    }

    @Test
    void theRunIsUnhealthyWhenACallCouldNotBeMade(@TempDir Path output) {
        assertThatThrownBy(() -> runner(output, executor()).assertHealthy())
                .isInstanceOf(ApiExplorerException.class)
                .hasMessageContaining("1 failed");
    }

    @Test
    void theMarkdownReportNamesWhatToConfigureNext(@TempDir Path output) throws IOException {
        // a required query parameter nobody can supply is the actionable case
        ApiExplorerReport report = run(output, executor());
        String markdown = Files.readString(Path.of(report.reportMarkdown()));

        assertThat(markdown)
                .contains("# API Explorer Report")
                .contains("demo")
                .contains("CONTRACT_MISMATCH");
    }

    @Test
    void disabledExplorationTouchesNothingButStillWritesAReport(@TempDir Path output) {
        ApiExplorerRunner runner = new ApiExplorerRunner(
                new OpenApiSpecParser(),
                new OperationSelector(),
                SafetyPolicy.from(properties(output, false)),
                new RequestPlanner(new RequestValueResolver(
                        new ApiExplorerProperties.ParameterProperties(Map.of(), Map.of()), new SchemaValueFactory())),
                executor(),
                new ResponseContractChecker(new ObjectMapper()),
                new ObservationFactory(redactor(), 4000),
                new ObjectMapper(),
                discoveryProperties(),
                properties(output, false));

        ApiExplorerReport report = runner.run();

        assertThat(report.enabled()).isFalse();
        assertThat(report.specs()).isEmpty();
        assertThat(Path.of(report.reportJson())).exists();
    }

    private ApiExplorerReport run(Path output, ExchangeExecutor executor) {
        return runner(output, executor).run();
    }

    private ApiExplorerRunner runner(Path output, ExchangeExecutor executor) {
        ApiExplorerProperties properties = properties(output, true);
        return new ApiExplorerRunner(
                new OpenApiSpecParser(),
                new OperationSelector(),
                SafetyPolicy.from(properties),
                new RequestPlanner(new RequestValueResolver(properties.parameters(), new SchemaValueFactory())),
                executor,
                new ResponseContractChecker(new ObjectMapper()),
                new ObservationFactory(redactor(), 4000),
                new ObjectMapper(),
                discoveryProperties(),
                properties);
    }

    private ApiExplorerProperties properties(Path output, boolean enabled) {
        return new ApiExplorerProperties(enabled, output.toString(), null, List.of(), null, null,
                null, null, null, null, null, null);
    }

    private ApiDiscoveryProperties discoveryProperties() {
        return new ApiDiscoveryProperties(true, null, null, null, null,
                Map.of(ExplorerFixtures.SPEC_ID, new ApiDiscoveryProperties.Spec(ExplorerFixtures.LOCATION)));
    }

    private Redactor redactor() {
        return new Redactor(new ObjectMapper(), List.of("authorization"), List.of("token", "password"));
    }

    private List<String> observationFiles(Path output) throws IOException {
        try (var files = Files.list(output.resolve("demo").resolve("observations"))) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    /**
     * One healthy endpoint, one that drifted from its schema, one that cannot be
     * reached at all — the three shapes the report has to tell apart.
     */
    private ExchangeExecutor executor() {
        Map<String, RuntimeExchange> responses = new HashMap<>();
        responses.put("GET /tasks", new RuntimeExchange(
                Map.of("Authorization", "Bearer super-secret"),
                null,
                200,
                "application/json",
                Map.of("Content-Type", "application/json"),
                "{\"items\":[{\"id\":\"t-1\",\"title\":\"Write tests\"}]}",
                12L,
                null));
        responses.put("GET /tasks/{taskId}", new RuntimeExchange(
                Map.of(), null, 200, "application/json", Map.of(),
                "{\"id\":\"t-1\"}", 8L, null));

        return new ExchangeExecutor() {
            @Override
            public RuntimeExchange execute(PreparedRequest request) {
                RuntimeExchange canned = responses.get(request.method() + " " + request.pathTemplate());
                return canned != null
                        ? canned
                        : RuntimeExchange.failed(Map.of(), "java.net.ConnectException: Connection refused", 3L);
            }

            @Override
            public String baseUrl() {
                return BASE_URL;
            }
        };
    }
}
