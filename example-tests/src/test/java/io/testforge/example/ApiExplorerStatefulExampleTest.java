package io.testforge.example;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.testforge.api.explorer.ApiExplorerReport;
import io.testforge.api.explorer.ApiExplorerRunner;
import io.testforge.api.explorer.ExtractionOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Read-only stateful exploration: the id the list returns is the id the item
 * call uses. Without the rule the explorer would send the document's example
 * id, which does not exist on the environment, and report a 404.
 */
@SpringBootTest(properties = {
        "forge.api-explorer.enabled=true",
        "forge.api-explorer.output-dir=build/api-explorer/stateful-example",
        "forge.api-discovery.specs.stateful.location=classpath:/openapi/stateful-demo.yaml",
        "forge.api-explorer.specs=stateful",
        "forge.api-explorer.extract[0].from=listTasks",
        "forge.api-explorer.extract[0].json-path=$.items[0].id",
        "forge.api-explorer.extract[0].into[0]=getTask.taskId"
})
class ApiExplorerStatefulExampleTest {

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
    void theItemCallUsesTheIdTheListReturned() {
        server.resetAll();
        server.stubFor(get(urlPathEqualTo("/api/v1/tasks"))
                .willReturn(okJson("{\"items\":[{\"id\":\"t-77\",\"title\":\"Real task\"}]}")));
        server.stubFor(get(urlPathEqualTo("/api/v1/tasks/t-77"))
                .willReturn(okJson("{\"id\":\"t-77\",\"title\":\"Real task\"}")));

        ApiExplorerReport report = explorer.run();

        assertThat(report.healthy()).isTrue();
        assertThat(report.specs()).singleElement().satisfies(spec -> {
            assertThat(spec.passed()).isEqualTo(2);
            assertThat(spec.extractions()).singleElement()
                    .extracting(ExtractionOutcome::status).isEqualTo(ExtractionOutcome.Status.EXTRACTED);
        });
        server.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/tasks/t-77")));
        server.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/tasks/task-from-the-docs")));
    }
}
