package io.testforge.example;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.testforge.api.explorer.ApiExplorerReport;
import io.testforge.api.explorer.ApiExplorerRunner;
import io.testforge.api.explorer.ExplorerOutcome;
import io.testforge.api.explorer.ObservationSummary;
import io.testforge.api.explorer.SkipReason;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The whole promise of the module in one test: point it at an OpenAPI document
 * and an environment, get back a map of what the API actually does.
 *
 * <p>Runs in the default build — the "environment" is an embedded WireMock, and
 * the document is the same {@code demo-api.yaml} the discovery and codegen
 * examples use.
 */
@SpringBootTest(properties = {
        "forge.api-explorer.enabled=true",
        "forge.api-explorer.output-dir=build/api-explorer/example",
        "forge.api-discovery.specs.demo.location=classpath:/openapi/demo-api.yaml"
})
class ApiExplorerExampleTest {

    private static final Path OUTPUT_DIR = Path.of("build/api-explorer/example");

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

    @BeforeEach
    void reset() {
        server.resetAll();
    }

    @Test
    void mapsAvailabilityWithoutTouchingWriteEndpoints() {
        server.stubFor(get(urlPathEqualTo("/api/v1/tasks"))
                .willReturn(okJson("{\"items\":[{\"id\":\"t-1\",\"title\":\"Demo\",\"status\":\"open\"}]}")));

        ApiExplorerReport report = explorer.run();

        assertThat(report.healthy()).isTrue();
        assertThat(report.specs()).singleElement().satisfies(spec -> {
            assertThat(spec.operations()).isEqualTo(2);
            assertThat(spec.passed()).isEqualTo(1);
            assertThat(spec.skipped()).isEqualTo(1);
        });

        // the document declares POST /api/v1/tasks; nothing was sent to it
        server.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/tasks")));
        server.verify(0, postRequestedFor(urlPathEqualTo("/api/v1/tasks")));

        assertThat(summary(report, "POST /api/v1/tasks").skipReason())
                .isEqualTo(SkipReason.METHOD_NOT_ENABLED);
    }

    @Test
    void reportsWhereTheServiceDisagreesWithItsOwnDocument() {
        // the document says a Task always has a status; this one does not, and
        // it carries a field the document never mentions
        server.stubFor(get(urlPathEqualTo("/api/v1/tasks"))
                .willReturn(okJson("{\"items\":[{\"id\":\"t-1\",\"title\":\"Demo\",\"owner\":\"nobody\"}]}")));

        ApiExplorerReport report = explorer.run();

        ObservationSummary listTasks = summary(report, "GET /api/v1/tasks");
        assertThat(listTasks.outcome()).isEqualTo(ExplorerOutcome.CONTRACT_MISMATCH);
        assertThat(listTasks.mismatches()).isEqualTo(2);

        // a mismatch is information, not a broken build, until a project says so
        assertThat(report.healthy()).isTrue();
    }

    @Test
    void writesDeterministicArtifactsAReviewerCanRead() throws IOException {
        server.stubFor(get(urlPathEqualTo("/api/v1/tasks"))
                .willReturn(okJson("{\"items\":[]}")));

        ApiExplorerReport report = explorer.run();

        assertThat(Path.of(report.reportJson())).exists();
        assertThat(Files.readString(Path.of(report.reportMarkdown())))
                .contains("# API Explorer Report")
                .contains("demo")
                .contains("GET /api/v1/tasks");

        Path observations = OUTPUT_DIR.resolve("demo").resolve("observations");
        assertThat(observations.resolve("listTasks".toLowerCase() + ".json")).exists();
        assertThat(Files.readString(observations.resolve("listtasks.json")))
                .contains("\"resolvedUrl\"")
                .contains("\"outcome\" : \"PASSED\"");
    }

    private ObservationSummary summary(ApiExplorerReport report, String key) {
        return report.specs().getFirst().observations().stream()
                .filter(observation -> observation.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No observation for " + key));
    }
}
