package io.testforge.api.explorer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.api.discovery.ApiDiscoveryProperties;
import io.testforge.api.discovery.OpenApiSpecParser;
import io.testforge.api.discovery.TestForgeApiDiscoveryAutoConfiguration;
import io.testforge.core.TestForgeCoreAutoConfiguration;
import io.testforge.http.Redactor;
import io.testforge.http.TestForgeHttpAutoConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Record once against a server, replay without one: same story, no traffic. */
class ReplayTest {

    private final List<String> sent = new ArrayList<>();

    private final ExchangeExecutor server = new ExchangeExecutor() {
        @Override
        public RuntimeExchange execute(PreparedRequest request) {
            sent.add(request.method() + " " + request.resolvedTarget());
            return switch (request.method() + " " + request.pathTemplate()) {
                case "GET /tasks" -> ok(200, "{\"items\":[{\"id\":\"t-1\",\"title\":\"a long enough title\"}]}");
                case "POST /tasks" -> ok(201, "{\"id\":\"new-1\",\"title\":\"x\"}");
                case "GET /tasks/{taskId}" -> ok(200, "{\"id\":\"new-1\",\"title\":\"x\"}");
                case "DELETE /tasks/{taskId}" -> ok(204, null);
                default -> RuntimeExchange.failed(Map.of(), "connection refused", 3);
            };
        }

        @Override
        public String baseUrl() {
            return "http://staging.example.test:8080";
        }
    };

    private static RuntimeExchange ok(int status, String body) {
        return new RuntimeExchange(Map.of(), null, status, "application/json",
                Map.of("Content-Type", "application/json"), body, 5L, null);
    }

    private static ApiExplorerProperties properties(Path output) {
        return new ApiExplorerProperties(true, output.toString(), null, List.of(),
                Set.of("GET", "POST", "DELETE"), true, null, null, null, null, null, null,
                List.of(new ExtractionRule("createTask", "$.id", List.of("getTask.taskId", "deleteTask.taskId"))),
                Map.of("createTask", "{\"title\":\"testforge-{{unique}}\"}"), null);
    }

    private static ApiExplorerReport run(Path output, ExchangeExecutor executor, int maxBodyChars) {
        ApiExplorerProperties properties = properties(output);
        return new ApiExplorerRunner(new OpenApiSpecParser(), new OperationSelector(), new SafetyPolicy(properties),
                new RequestPlanner(new RequestValueResolver(properties.parameters(), new SchemaValueFactory()),
                        properties.bodies()),
                executor, new ResponseContractChecker(new ObjectMapper()),
                new ObservationFactory(new Redactor(new ObjectMapper(), List.of(), List.of()), maxBodyChars),
                new ObjectMapper(),
                new ApiDiscoveryProperties(true, null, null, null, null,
                        Map.of(ExplorerFixtures.SPEC_ID, new ApiDiscoveryProperties.Spec(ExplorerFixtures.LOCATION))),
                properties).run();
    }

    private static Map<String, String> outcomes(ApiExplorerReport report) {
        Map<String, String> outcomes = new LinkedHashMap<>();
        report.specs().getFirst().observations().forEach(observation ->
                outcomes.put(observation.key(), observation.outcome() + "/" + observation.status()));
        return outcomes;
    }

    @Test
    void aReplayTellsTheSameStoryWithoutTouchingTheNetwork(@TempDir Path temp) throws Exception {
        ApiExplorerReport recorded = run(temp.resolve("recorded"), server, 4000);
        int recordedCalls = sent.size();

        ApiExplorerReport replayed = run(temp.resolve("replayed"),
                new ReplayExchangeExecutor(temp.resolve("recorded"), temp.resolve("replayed")), 4000);

        assertThat(sent).as("nothing reached the server during replay").hasSize(recordedCalls);
        assertThat(outcomes(replayed)).isEqualTo(outcomes(recorded));
        assertThat(replayed.specs().getFirst().extractions()).singleElement()
                .extracting(ExtractionOutcome::status).isEqualTo(ExtractionOutcome.Status.EXTRACTED);
        assertThat(replayed.specs().getFirst().baseUrl()).startsWith("replay:");
        assertThat(Files.readString(Path.of(recorded.reportMarkdown())))
                .contains("**write requests sent: 2**");
        assertThat(Files.readString(Path.of(replayed.reportMarkdown())))
                .contains("**write requests sent: 0**")
                .doesNotContain("was changed by this run");
    }

    @Test
    void aRecordedFailureReplaysAsAFailureWithItsReason(@TempDir Path temp) {
        run(temp.resolve("recorded"), server, 4000);
        ReplayExchangeExecutor replay = new ReplayExchangeExecutor(temp.resolve("recorded"), temp.resolve("out"));

        assertThat(replay.execute(new PreparedRequest("GET", "/reports", Map.of(), Map.of("from", "x"))).error())
                .startsWith("no recorded exchange for GET /reports");
    }

    @Test
    void aTruncatedRecordingIsReportedNotReplayedHalfway(@TempDir Path temp) {
        run(temp.resolve("recorded"), server, 20);

        ApiExplorerReport replayed = run(temp.resolve("replayed"),
                new ReplayExchangeExecutor(temp.resolve("recorded"), temp.resolve("replayed")), 20);

        assertThat(replayed.specs().getFirst().observations())
                .filteredOn(observation -> observation.key().equals("GET /tasks"))
                .singleElement()
                .satisfies(observation -> {
                    assertThat(observation.outcome()).isEqualTo(ExplorerOutcome.FAILED);
                    assertThat(observation.reason()).contains("truncated").contains("max-body-chars");
                });
    }

    @Test
    void replayingIntoTheRecordingItselfIsRefused(@TempDir Path temp) {
        run(temp.resolve("recorded"), server, 4000);

        assertThatThrownBy(() -> new ReplayExchangeExecutor(temp.resolve("recorded"), temp.resolve("recorded")))
                .hasMessageContaining("must differ from output-dir");
    }

    @Test
    void anEmptyRecordingIsRefused(@TempDir Path temp) throws Exception {
        Files.createDirectories(temp.resolve("empty"));

        assertThatThrownBy(() -> new ReplayExchangeExecutor(temp.resolve("empty"), temp.resolve("out")))
                .hasMessageContaining("No recorded observations");
    }

    @Test
    void theBaseUrlIsNotPartOfARequestsIdentity() {
        assertThat(ReplayExchangeExecutor.target("http://host:8080/tasks/1?x=2")).isEqualTo("/tasks/1?x=2");
        assertThat(ReplayExchangeExecutor.target("https://host")).isEqualTo("/");
    }

    @Test
    void replayFromSwapsTheExecutorAndNeedsNoNetwork(@TempDir Path temp) {
        run(temp.resolve("recorded"), server, 4000);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TestForgeCoreAutoConfiguration.class,
                        TestForgeHttpAutoConfiguration.class, TestForgeApiDiscoveryAutoConfiguration.class,
                        TestForgeApiExplorerAutoConfiguration.class))
                .withPropertyValues(
                        "forge.api-explorer.enabled=true",
                        "forge.api-explorer.output-dir=" + temp.resolve("replayed"),
                        "forge.api-explorer.replay-from=" + temp.resolve("recorded"))
                .run(context -> assertThat(context.getBean(ExchangeExecutor.class))
                        .isInstanceOf(ReplayExchangeExecutor.class));
    }
}
