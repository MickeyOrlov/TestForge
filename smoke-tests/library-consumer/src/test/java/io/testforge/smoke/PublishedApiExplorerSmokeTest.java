package io.testforge.smoke;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.testforge.api.explorer.ApiExplorerReport;
import io.testforge.api.explorer.ApiExplorerRunner;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The explorer as an external consumer sees it: resolved from the published
 * artifact, auto-configured from the JAR's own metadata, driving the published
 * {@code module-http} and {@code module-api-discovery} transitively.
 */
@SpringBootTest(
        classes = SmokeTestApplication.class,
        properties = {
                "forge.api-explorer.enabled=true",
                "forge.api-explorer.output-dir=build/api-explorer-smoke",
                "forge.api-discovery.specs.demo.location=classpath:/openapi/demo-api.yaml"
        })
class PublishedApiExplorerSmokeTest {

    static WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        server.start();
        // exactly the fields this copy of demo-api.yaml declares for Task —
        // one extra field and the run would report UNDOCUMENTED_FIELD, which is
        // the checker doing its job
        server.stubFor(get(urlPathEqualTo("/api/v1/tasks"))
                .willReturn(okJson("{\"items\":[{\"id\":\"t-1\",\"title\":\"Demo\"}]}")));
    }

    @DynamicPropertySource
    static void forgeProperties(DynamicPropertyRegistry registry) {
        registry.add("forge.http.base-url", () -> "http://localhost:" + server.port());
    }

    @Autowired
    ApiExplorerRunner explorer;

    @Test
    void exploresAnApiFromThePublishedLibrary() {
        ApiExplorerReport report = explorer.assertHealthy();

        assertThat(report.specs()).singleElement().satisfies(spec -> {
            assertThat(spec.specId()).isEqualTo("demo");
            assertThat(spec.passed()).isEqualTo(1);
            assertThat(spec.skipped()).isEqualTo(1);
            assertThat(Path.of(spec.observationsDir())).exists();
        });

        // safe-by-default survives the trip through Maven
        server.verify(0, postRequestedFor(urlPathEqualTo("/api/v1/tasks")));
        assertThat(Path.of(report.reportMarkdown())).exists();
    }
}
