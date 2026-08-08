package io.testforge.example;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
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
 * Body fuzzing against an embedded service that validates one field and forgets
 * the other — the defect this increment exists to find.
 *
 * <p>Write methods are enabled here explicitly, which is the whole point: they
 * are not enabled by the presence of a request body, only by a project saying
 * so twice. {@code ApiFuzzExampleTest} shows the same document with the default
 * configuration, where nothing is posted at all.
 */
@SpringBootTest(properties = {
        "forge.api-fuzz.enabled=true",
        "forge.api-fuzz.seed=20260101",
        "forge.api-fuzz.methods=GET,POST",
        "forge.api-fuzz.allow-unsafe-methods=true",
        "forge.api-fuzz.output-dir=build/api-fuzz/body-example",
        "forge.api-discovery.specs.demo.location=classpath:/openapi/fuzz-demo-api.yaml"
})
class ApiFuzzBodyExampleTest {

    static WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        server.start();
    }

    @DynamicPropertySource
    static void forgeProperties(DynamicPropertyRegistry registry) {
        registry.add("forge.http.base-url", () -> "http://localhost:" + server.port());
    }

    @Autowired
    ApiFuzzRunner fuzz;

    @BeforeEach
    void stubService() {
        server.resetAll();
        server.stubFor(get(urlPathMatching("/api/v1/tasks/.*")).willReturn(okJson("{\"id\":\"t-1\"}")));

        // the service checks the title and never looks at the priority, so a
        // value outside the declared 1..5 sails through
        server.stubFor(post(urlPathEqualTo("/api/v1/tasks"))
                .atPriority(1)
                .withRequestBody(equalToJson("{\"title\":\"aaa\",\"priority\":1}", true, true))
                .willReturn(aResponse().withStatus(201)));
        server.stubFor(post(urlPathEqualTo("/api/v1/tasks"))
                .atPriority(5)
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                        .matchingJsonPath("$[?(@.title.length() < 3)]"))
                .willReturn(aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"title too short\"}")));
        server.stubFor(post(urlPathEqualTo("/api/v1/tasks"))
                .atPriority(10)
                .willReturn(aResponse().withStatus(201)));
    }

    @Test
    void walksTheBodySchemaAndFindsTheUncheckedField() throws IOException {
        ApiFuzzReport report = fuzz.run();

        // priority is declared 1..5; the service takes 6 anyway
        assertThat(report.findings())
                .filteredOn(finding -> finding.verdict() == FuzzVerdict.OVER_PERMISSIVE)
                .extracting(finding -> finding.fuzzCase().id())
                .contains("createTask/body:$.priority/ABOVE_MAXIMUM");

        FuzzObservation finding = report.findings().stream()
                .filter(observation -> observation.fuzzCase().id().equals("createTask/body:$.priority/ABOVE_MAXIMUM"))
                .findFirst()
                .orElseThrow();

        assertThat(finding.status()).isEqualTo(201);
        assertThat(finding.requestFragment()).contains("$.priority");

        assertThat(Files.readString(Path.of(report.reportMarkdown())))
                .contains("createTask/body:$.priority/ABOVE_MAXIMUM");
    }

    @Test
    void oneFieldChangesPerRequestSoAFindingPointsAtOneField() {
        fuzz.run();

        // the title case leaves priority at its valid baseline, and vice versa
        server.verify(postRequestedFor(urlPathEqualTo("/api/v1/tasks"))
                .withRequestBody(equalToJson("{\"title\":\"aa\",\"priority\":1}", true, true)));
        server.verify(postRequestedFor(urlPathEqualTo("/api/v1/tasks"))
                .withRequestBody(equalToJson("{\"title\":\"aaa\",\"priority\":6}", true, true)));
    }

    @Test
    void theEnvelopeIsProbedSeparatelyFromTheSchema() {
        ApiFuzzReport report = fuzz.run();

        // broken JSON reaches the body parser rather than the validation the
        // team wrote, which is where a stack trace tends to escape
        server.verify(postRequestedFor(urlPathEqualTo("/api/v1/tasks"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                        .equalTo("{\"testforge\": ")));
        server.verify(postRequestedFor(urlPathEqualTo("/api/v1/tasks"))
                .withHeader("Content-Type", com.github.tomakehurst.wiremock.client.WireMock
                        .containing("text/plain")));

        // and they are counted apart, so constraint coverage keeps meaning
        // what it says
        assertThat(report.specs().getFirst().operations())
                .filteredOn(operation -> "createTask".equals(operation.operationId()))
                .singleElement()
                .satisfies(operation -> {
                    assertThat(operation.coverage().outcomes().protocolMutations()).isPositive();
                    assertThat(operation.coverage().outcomes().schemaMutations()).isPositive();
                });
    }

    @Test
    void everyRequestCarriesAJsonContentType() {
        fuzz.run();

        // every schema case does, at least: the one request that deliberately
        // does not is the UNSUPPORTED_CONTENT_TYPE protocol case above
        server.verify(postRequestedFor(urlPathEqualTo("/api/v1/tasks"))
                .withHeader("Content-Type", com.github.tomakehurst.wiremock.client.WireMock
                        .containing("application/json")));
    }
}
