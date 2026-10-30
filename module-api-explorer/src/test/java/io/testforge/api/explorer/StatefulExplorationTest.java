package io.testforge.api.explorer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.api.discovery.ApiDiscoveryProperties;
import io.testforge.api.discovery.OpenApiSpecParser;
import io.testforge.http.Redactor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Values from one response feeding the next request — read-only, no request bodies. */
class StatefulExplorationTest {

    private static final String TASK_JSON = "{\"id\":\"task-42\",\"title\":\"Write tests\"}";

    private final List<String> sent = new ArrayList<>();

    /** A server where only task-42 exists: anything invented gets a 404. */
    private final ExchangeExecutor server = new ExchangeExecutor() {
        @Override
        public RuntimeExchange execute(PreparedRequest request) {
            sent.add(request.method() + " " + request.resolvedTarget());
            String key = request.method() + " " + request.pathTemplate();
            return switch (key) {
                case "GET /tasks" -> ok("{\"items\":[" + TASK_JSON + "]}");
                case "GET /tasks/{taskId}" -> "task-42".equals(request.pathParameters().get("taskId"))
                        ? ok(TASK_JSON)
                        : new RuntimeExchange(Map.of(), null, 404, "application/json", Map.of(), "{}", 1L, null);
                default -> ok("{}");
            };
        }

        @Override
        public String baseUrl() {
            return "http://localhost:0";
        }
    };

    private static RuntimeExchange ok(String body) {
        return new RuntimeExchange(Map.of(), null, 200, "application/json",
                Map.of("Content-Type", "application/json"), body, 1L, null);
    }

    private ApiExplorerReport run(Path output, List<ExtractionRule> rules) {
        ApiExplorerProperties properties = new ApiExplorerProperties(true, output.toString(), null, List.of(),
                null, null, null, null, null, null, null, null, rules, null, null);
        return new ApiExplorerRunner(
                new OpenApiSpecParser(),
                new OperationSelector(),
                new SafetyPolicy(properties),
                new RequestPlanner(new RequestValueResolver(properties.parameters(), new SchemaValueFactory())),
                server,
                new ResponseContractChecker(new ObjectMapper()),
                new ObservationFactory(new Redactor(new ObjectMapper(), List.of(), List.of()), 4000),
                new ObjectMapper(),
                new ApiDiscoveryProperties(true, null, null, null, null,
                        Map.of(ExplorerFixtures.SPEC_ID, new ApiDiscoveryProperties.Spec(ExplorerFixtures.LOCATION))),
                properties).run();
    }

    private static ObservationSummary observation(ApiExplorerReport report, String key) {
        return report.specs().getFirst().observations().stream()
                .filter(summary -> summary.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void anIdFromTheListReachesARealItem(@TempDir Path output) throws Exception {
        ApiExplorerReport report = run(output, List.of(
                new ExtractionRule("listTasks", "$.items[0].id", List.of("getTask.taskId"))));

        assertThat(sent).contains("GET /tasks/task-42");
        assertThat(sent.indexOf("GET /tasks/task-42")).isGreaterThan(sent.indexOf("GET /tasks?limit=25"));
        assertThat(observation(report, "GET /tasks/{taskId}").status()).isEqualTo(200);
        assertThat(report.specs().getFirst().extractions()).singleElement()
                .satisfies(outcome -> assertThat(outcome.status()).isEqualTo(ExtractionOutcome.Status.EXTRACTED));

        String observationJson = Files.readString(Path.of(observation(report, "GET /tasks/{taskId}").artifact()));
        assertThat(observationJson).contains("\"source\" : \"EXTRACTED\"");
    }

    @Test
    void withoutARuleTheExplorerInventsAValueAndMisses(@TempDir Path output) {
        ApiExplorerReport report = run(output, List.of());

        assertThat(observation(report, "GET /tasks/{taskId}").status()).isEqualTo(404);
        assertThat(report.specs().getFirst().extractions()).isEmpty();
    }

    @Test
    void theReportSaysWhyAChainDidNotWorkWithoutPrintingValues(@TempDir Path output) throws Exception {
        ApiExplorerReport report = run(output, List.of(
                new ExtractionRule("listTasks", "$.items[0].id", List.of("getTask.taskId")),
                new ExtractionRule("listTasks", "$.items[9].id", List.of("deleteTask.taskId")),
                new ExtractionRule("listTasks", "$.items", List.of("getTask.taskId")),
                new ExtractionRule("createTask", "$.id", List.of("getTask.taskId"))));

        assertThat(report.specs().getFirst().extractions()).extracting(ExtractionOutcome::status).containsExactly(
                ExtractionOutcome.Status.EXTRACTED,
                ExtractionOutcome.Status.NOT_FOUND,
                ExtractionOutcome.Status.NOT_A_VALUE,
                ExtractionOutcome.Status.PRODUCER_NOT_RUN);

        String markdown = Files.readString(Path.of(report.reportMarkdown()));
        assertThat(markdown).contains("### Value extraction").contains("json-path matched nothing")
                .contains("the producing operation was skipped");
        assertThat(Files.readString(Path.of(report.reportJson())) + markdown).doesNotContain("task-42");
    }

    @Test
    void producersRunBeforeTheirConsumersWhateverTheDocumentOrder() {
        ExplorableOperation list = ExplorerFixtures.operation("listTasks");
        ExplorableOperation get = ExplorerFixtures.operation("getTask");
        ExplorableOperation reports = ExplorerFixtures.operation("listReports");

        List<ExplorableOperation> ordered = OperationOrder.forExtraction(List.of(get, reports, list),
                List.of(new ExtractionRule("listTasks", "$.items[0].id", List.of("getTask.taskId"))));

        assertThat(ordered).extracting(ExplorableOperation::operationId)
                .containsExactly("listReports", "listTasks", "getTask");
    }

    @Test
    void aCycleKeepsTheOriginalOrder() {
        ExplorableOperation list = ExplorerFixtures.operation("listTasks");
        ExplorableOperation get = ExplorerFixtures.operation("getTask");

        List<ExplorableOperation> ordered = OperationOrder.forExtraction(List.of(get, list), List.of(
                new ExtractionRule("listTasks", "$.a", List.of("getTask.taskId")),
                new ExtractionRule("getTask", "$.b", List.of("listTasks.limit"))));

        assertThat(ordered).extracting(ExplorableOperation::operationId).containsExactly("getTask", "listTasks");
    }

    @Test
    void producerFailuresAndNonJsonAreReported() {
        ExtractionRule rule = new ExtractionRule("listTasks", "$.id", List.of("taskId"));
        ExtractedValues failed = new ExtractedValues(List.of(rule), new ObjectMapper());
        failed.record("listTasks", new RuntimeExchange(Map.of(), null, 500, "application/json", Map.of(), "{}", 1L, null));
        ExtractedValues text = new ExtractedValues(List.of(rule), new ObjectMapper());
        text.record("listTasks", new RuntimeExchange(Map.of(), null, 200, "text/plain", Map.of(), "hello", 1L, null));

        assertThat(failed.outcomes().getFirst().status()).isEqualTo(ExtractionOutcome.Status.PRODUCER_FAILED);
        assertThat(failed.outcomes().getFirst().detail()).isEqualTo("status 500");
        assertThat(text.outcomes().getFirst().status()).isEqualTo(ExtractionOutcome.Status.NOT_JSON);
        assertThat(failed.find("anyOperation", "taskId")).isEmpty();
    }

    @Test
    void aBareParameterNameFeedsEveryOperation() {
        ExtractionRule rule = new ExtractionRule("currentUser", "$.tenant", List.of("tenantId"));
        ExtractedValues values = new ExtractedValues(List.of(rule), new ObjectMapper());
        values.record("currentUser", ok("{\"tenant\":\"t-7\"}"));

        assertThat(values.find("listOrders", "tenantId")).contains("t-7");
        assertThat(values.find("listOrders", "other")).isEmpty();
        assertThat(rule.mayFeed("currentUser")).as("never feeds itself").isFalse();
    }

    @Test
    void aConfiguredValueStillWinsOverAnExtractedOne() {
        ExtractedValues values = new ExtractedValues(
                List.of(new ExtractionRule("listTasks", "$.items[0].id", List.of("getTask.taskId"))), new ObjectMapper());
        values.record("listTasks", ok("{\"items\":[" + TASK_JSON + "]}"));
        RequestValueResolver resolver = new RequestValueResolver(
                new ApiExplorerProperties.ParameterProperties(Map.of(), Map.of("getTask", Map.of("taskId", "pinned"))),
                new SchemaValueFactory());

        ExplorableOperation get = ExplorerFixtures.operation("getTask");
        assertThat(resolver.resolve(get, get.parameters().getFirst(), values))
                .get().extracting(ParameterBinding::source, ParameterBinding::value)
                .containsExactly(ValueSource.CONFIGURED, "pinned");
    }

    @Test
    void rulesAreValidated() {
        assertThatThrownBy(() -> new ExtractionRule(" ", "$.id", List.of("x"))).hasMessageContaining("'from'");
        assertThatThrownBy(() -> new ExtractionRule("a", "", List.of("x"))).hasMessageContaining("json-path");
        assertThatThrownBy(() -> new ExtractionRule("a", "$.id", List.of())).hasMessageContaining("'into'");
    }
}
