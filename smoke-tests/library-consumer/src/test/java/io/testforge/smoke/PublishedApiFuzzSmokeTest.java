package io.testforge.smoke;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.testforge.api.fuzz.ApiFuzzReport;
import io.testforge.api.fuzz.ApiFuzzRunner;
import io.testforge.api.fuzz.FuzzObservation;
import io.testforge.api.fuzz.FuzzVerdict;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The fuzz module as an external consumer sees it: resolved from the published
 * artifact, auto-configured from the JAR's metadata, driving the published
 * explorer and HTTP modules transitively.
 */
@SpringBootTest(
        classes = SmokeTestApplication.class,
        properties = {
                "forge.api-fuzz.enabled=true",
                "forge.api-fuzz.seed=20260101",
                "forge.api-fuzz.output-dir=build/api-fuzz-smoke",
                "forge.api-discovery.specs.demo.location=classpath:/openapi/fuzz-demo-api.yaml"
        })
class PublishedApiFuzzSmokeTest {

    static WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        server.start();
        server.stubFor(get(urlPathMatching("/api/v1/tasks/.{9,}"))
                .atPriority(1)
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"internal error\"}")));
        server.stubFor(get(urlPathMatching("/api/v1/tasks/.*"))
                .atPriority(10)
                .willReturn(okJson("{\"id\":\"t-1\"}")));
    }

    @DynamicPropertySource
    static void forgeProperties(DynamicPropertyRegistry registry) {
        registry.add("forge.http.base-url", () -> "http://localhost:" + server.port());
    }

    @Autowired
    ApiFuzzRunner fuzz;

    @Test
    void fuzzesAnApiFromThePublishedLibrary() {
        ApiFuzzReport report = fuzz.run();

        assertThat(report.findings())
                .extracting(FuzzObservation::verdict)
                .contains(FuzzVerdict.SERVER_ERROR);
        assertThat(report.findings())
                .extracting(finding -> finding.fuzzCase().id())
                .allSatisfy(id -> assertThat(id).contains("taskId"));

        // safe-by-default survives the trip through Maven
        server.verify(0, postRequestedFor(urlPathEqualTo("/api/v1/tasks")));
    }
}
