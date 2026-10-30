package io.testforge.example;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.testforge.api.explorer.ApiExplorerReport;
import io.testforge.api.explorer.ApiExplorerRunner;
import io.testforge.api.explorer.ProposedRule;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The first run on a new API, before anyone wrote a rule: the report proposes
 * the links the document suggests, as YAML to paste into
 * {@code forge.api-explorer.extract}. Nothing is applied on its own — the item
 * call still uses the document's example id until a human accepts the rule
 * (see {@code ApiExplorerStatefulExampleTest} for the run after that).
 */
@SpringBootTest(properties = {
        "forge.api-explorer.enabled=true",
        "forge.api-explorer.output-dir=build/api-explorer/proposal-example",
        "forge.api-discovery.specs.stateful.location=classpath:/openapi/stateful-demo.yaml",
        "forge.api-explorer.specs=stateful"
})
class ApiExplorerProposalExampleTest {

    static WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        server.start();
    }

    @DynamicPropertySource
    static void forgeProperties(DynamicPropertyRegistry registry) {
        registry.add("forge.http.base-url", () -> "http://localhost:" + server.port());
    }

    @Autowired
    ApiExplorerRunner explorer;

    @Test
    void theReportProposesTheListToItemLink() throws Exception {
        server.resetAll();
        server.stubFor(get(urlPathEqualTo("/api/v1/tasks"))
                .willReturn(okJson("{\"items\":[{\"id\":\"t-77\",\"title\":\"Real task\"}]}")));

        ApiExplorerReport report = explorer.run();

        assertThat(report.specs().getFirst().proposals()).singleElement().satisfies(proposal -> {
            assertThat(proposal).extracting(ProposedRule::from, ProposedRule::jsonPath, ProposedRule::into)
                    .containsExactly("listTasks", "$.items[0].id", "getTask.taskId");
        });
        assertThat(Files.readString(Path.of(report.reportMarkdown())))
                .contains("### Proposed extraction rules")
                .contains("into: [getTask.taskId]");
        assertThat(report.specs().getFirst().observations())
                .filteredOn(observation -> observation.key().equals("GET /api/v1/tasks/{taskId}"))
                .singleElement()
                .satisfies(observation -> assertThat(observation.status()).as("not applied").isEqualTo(404));
    }
}
