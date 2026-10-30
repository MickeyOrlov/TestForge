package io.testforge.example;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.testforge.api.explorer.ApiExplorerReport;
import io.testforge.api.explorer.ApiExplorerRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A write chain: create a task from a body template, read it back by the id the
 * create returned, delete it last. Needs both write keys — listing POST/DELETE
 * alone is not enough — and never against a shared environment.
 */
@SpringBootTest(properties = {
        "forge.api-explorer.enabled=true",
        "forge.api-explorer.output-dir=build/api-explorer/write-chain-example",
        "forge.api-discovery.specs.writes.location=classpath:/openapi/write-chain-demo.yaml",
        "forge.api-explorer.specs=writes",
        "forge.api-explorer.methods=GET,POST,DELETE",
        "forge.api-explorer.allow-unsafe-methods=true",
        "forge.api-explorer.bodies.createTask={\"title\": \"testforge-{{unique}}\"}",
        "forge.api-explorer.extract[0].from=createTask",
        "forge.api-explorer.extract[0].json-path=$.id",
        "forge.api-explorer.extract[0].into[0]=getTask.taskId",
        "forge.api-explorer.extract[0].into[1]=deleteTask.taskId"
})
class ApiExplorerWriteChainExampleTest {

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
    void createsReadsAndDeletesOneTask() throws Exception {
        server.resetAll();
        server.stubFor(post(urlPathEqualTo("/api/v1/tasks"))
                .withRequestBody(matchingJsonPath("$.title"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"t-new\",\"title\":\"created\"}")));
        server.stubFor(get(urlPathEqualTo("/api/v1/tasks/t-new"))
                .willReturn(okJson("{\"id\":\"t-new\",\"title\":\"created\"}")));
        server.stubFor(delete(urlPathEqualTo("/api/v1/tasks/t-new")).willReturn(aResponse().withStatus(204)));

        ApiExplorerReport report = explorer.run();

        assertThat(report.specs().getFirst().passed()).isEqualTo(3);
        List<LoggedRequest> creates = server.findAll(postRequestedFor(urlPathEqualTo("/api/v1/tasks"))
                .withHeader("Content-Type", equalTo("application/json")));
        assertThat(creates).singleElement()
                .satisfies(create -> assertThat(create.getBodyAsString()).matches(".*testforge-tf-[0-9a-f]{8}.*"));
        server.verify(1, deleteRequestedFor(urlPathEqualTo("/api/v1/tasks/t-new")));
        assertThat(server.getAllServeEvents().getFirst().getRequest().getMethod().getName())
                .as("WireMock lists newest first: the delete was the last call").isEqualTo("DELETE");
        assertThat(Files.readString(Path.of(report.reportMarkdown()))).contains("**write requests sent: 2**");
    }
}
