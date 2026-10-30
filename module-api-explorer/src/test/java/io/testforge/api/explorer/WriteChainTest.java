package io.testforge.api.explorer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.api.discovery.ApiDiscoveryProperties;
import io.testforge.api.discovery.OpenApiSpecParser;
import io.testforge.http.Redactor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Create, read, delete — behind both write keys, with a body only from a template. */
class WriteChainTest {

    private final List<String> sent = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private final Set<String> existing = new HashSet<>();

    /** A tiny in-memory task service. */
    private final ExchangeExecutor server = new ExchangeExecutor() {
        @Override
        public RuntimeExchange execute(PreparedRequest request) {
            sent.add(request.method() + " " + request.resolvedPath());
            String id = request.pathParameters().get("taskId");
            return switch (request.method() + " " + request.pathTemplate()) {
                case "POST /tasks" -> {
                    bodies.add(request.body() + " as " + request.contentType());
                    existing.add("new-1");
                    yield exchange(request, 201, "{\"id\":\"new-1\",\"title\":\"x\"}");
                }
                case "GET /tasks/{taskId}" -> existing.contains(id)
                        ? exchange(request, 200, "{\"id\":\"" + id + "\",\"title\":\"x\"}")
                        : exchange(request, 404, "{}");
                case "DELETE /tasks/{taskId}" -> existing.remove(id)
                        ? exchange(request, 204, null)
                        : exchange(request, 404, "{}");
                default -> exchange(request, 200, "{\"items\":[]}");
            };
        }

        @Override
        public String baseUrl() {
            return "http://staging.example.test";
        }
    };

    private static RuntimeExchange exchange(PreparedRequest request, int status, String body) {
        return new RuntimeExchange(Map.of(), request.body(), status, "application/json", Map.of(), body, 1L, null);
    }

    private static final List<ExtractionRule> CREATED_ID = List.of(
            new ExtractionRule("createTask", "$.id", List.of("getTask.taskId", "deleteTask.taskId")));

    private ApiExplorerReport run(Path output, boolean allowUnsafe, Map<String, String> templates) {
        ApiExplorerProperties properties = new ApiExplorerProperties(true, output.toString(), null, List.of(),
                Set.of("GET", "POST", "DELETE"), allowUnsafe, null, List.of("/reports"), null, null, null, null,
                CREATED_ID, templates, null);
        return new ApiExplorerRunner(new OpenApiSpecParser(), new OperationSelector(), new SafetyPolicy(properties),
                new RequestPlanner(new RequestValueResolver(properties.parameters(), new SchemaValueFactory()),
                        properties.bodies()),
                server, new ResponseContractChecker(new ObjectMapper()),
                new ObservationFactory(new Redactor(new ObjectMapper(), List.of(), List.of("password")), 4000),
                new ObjectMapper(),
                new ApiDiscoveryProperties(true, null, null, null, null,
                        Map.of(ExplorerFixtures.SPEC_ID, new ApiDiscoveryProperties.Spec(ExplorerFixtures.LOCATION))),
                properties).run();
    }

    @Test
    void createsReadsThenDeletesTheSameObject(@TempDir Path output) throws Exception {
        ApiExplorerReport report = run(output, true,
                Map.of("createTask", "{\"title\":\"testforge-{{unique}}\",\"password\":\"p4ss\"}"));

        assertThat(sent).containsSubsequence("POST /tasks", "GET /tasks/new-1", "DELETE /tasks/new-1");
        assertThat(sent.getLast()).as("the delete runs last").isEqualTo("DELETE /tasks/new-1");
        assertThat(existing).as("the chain cleaned up after itself").isEmpty();
        assertThat(bodies).singleElement().asString()
                .matches("\\{\"title\":\"testforge-tf-[0-9a-f]{8}\",\"password\":\"p4ss\"} as application/json");

        String markdown = Files.readString(Path.of(report.reportMarkdown()));
        assertThat(markdown).contains("**write requests sent: 2**").contains("http://staging.example.test");

        ObservationSummary create = report.specs().getFirst().observations().stream()
                .filter(o -> o.key().equals("POST /tasks")).findFirst().orElseThrow();
        assertThat(Files.readString(Path.of(create.artifact())))
                .as("bodies are redacted like any other request")
                .doesNotContain("p4ss");
    }

    @Test
    void withoutTheSecondKeyNothingIsWrittenEvenWithTemplatesAndRules(@TempDir Path output) throws Exception {
        ApiExplorerReport report = run(output, false, Map.of("createTask", "{\"title\":\"x\"}"));

        assertThat(sent).noneMatch(request -> request.startsWith("POST") || request.startsWith("DELETE"));
        assertThat(report.specs().getFirst().extractions()).singleElement()
                .extracting(ExtractionOutcome::status).isEqualTo(ExtractionOutcome.Status.PRODUCER_NOT_RUN);
        assertThat(Files.readString(Path.of(report.reportMarkdown()))).doesNotContain("write requests sent");
    }

    @Test
    void aRequiredBodyWithoutATemplateIsStillSkippedWithTheFix(@TempDir Path output) {
        ApiExplorerReport report = run(output, true, Map.of());

        assertThat(sent).noneMatch(request -> request.startsWith("POST"));
        assertThat(report.specs().getFirst().observations()).filteredOn(o -> o.key().equals("POST /tasks"))
                .singleElement()
                .satisfies(o -> assertThat(o.reason()).contains("configure forge.api-explorer.bodies.createTask"));
    }

    @Test
    void everyRenderedBodyGetsItsOwnUniqueToken() {
        RequestPlanner planner = new RequestPlanner(
                new RequestValueResolver(new ApiExplorerProperties.ParameterProperties(Map.of(), Map.of()),
                        new SchemaValueFactory()),
                Map.of("createTask", "{\"a\":\"{{unique}}\",\"b\":\"{{unique}}\"}"));
        ExplorableOperation create = ExplorerFixtures.operation("createTask");

        String first = planner.plan(create).request().body();
        String second = planner.plan(create).request().body();

        assertThat(first).isNotEqualTo(second).doesNotContain("{{unique}}");
        assertThat(first.split("tf-")).hasSize(3);
    }

    @Test
    void deletesRunAfterEverythingElseEvenWithoutRules() {
        List<ExplorableOperation> ordered = OperationOrder.forExtraction(ExplorerFixtures.operations(), List.of());

        assertThat(ordered.getLast().method()).isEqualTo("DELETE");
        assertThat(ordered).extracting(ExplorableOperation::key)
                .containsSubsequence("GET /tasks/{taskId}", "DELETE /tasks/{taskId}");
    }
}
