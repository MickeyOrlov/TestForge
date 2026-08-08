package io.testforge.example;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.testforge.api.fuzz.ApiFuzzReport;
import io.testforge.api.fuzz.ControlOutcome;
import io.testforge.api.fuzz.ApiFuzzRunner;
import io.testforge.api.fuzz.FuzzObservation;
import io.testforge.api.fuzz.FuzzEvidenceKind;
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
 * Fuzzing an embedded service with a specific, plausible defect: it crashes on
 * an identifier longer than its own document allows, instead of rejecting it.
 *
 * <p>Runs in the default build — the "environment" is WireMock, nothing leaves
 * the machine, and the write endpoint the document declares is never called.
 */
@SpringBootTest(properties = {
        "forge.api-fuzz.enabled=true",
        "forge.api-fuzz.seed=20260101",
        "forge.api-fuzz.output-dir=build/api-fuzz/example",
        "forge.api-discovery.specs.demo.location=classpath:/openapi/fuzz-demo-api.yaml"
})
class ApiFuzzExampleTest {

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

        // the defect: anything past the declared maximum blows up instead of
        // being refused with a 400. Priority 1 so it wins over the default
        // below — the repository's usual convention for scoped stubs
        server.stubFor(get(urlPathMatching("/api/v1/tasks/.{9,}"))
                .atPriority(1)
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"internal error\"}")));

        server.stubFor(get(urlPathMatching("/api/v1/tasks/.*"))
                .atPriority(10)
                .willReturn(okJson("{\"id\":\"t-1\"}")));
    }

    @Test
    void findsTheCrashAndNamesTheCaseThatCausedIt() throws IOException {
        ApiFuzzReport report = fuzz.run();

        FuzzObservation crash = report.findings().stream()
                .filter(finding -> finding.has(FuzzEvidenceKind.SERVER_ERROR))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected the over-long identifier to crash the service"));

        assertThat(crash.fuzzCase().parameterName()).isEqualTo("taskId");
        assertThat(crash.status()).isEqualTo(500);
        assertThat(report.healthy()).isFalse();

        // the report hands back the exact configuration to repeat this one call
        assertThat(Files.readString(Path.of(report.reportMarkdown())))
                .contains("# API Fuzz Report")
                .contains("### Reproduce a single case")
                .contains(crash.fuzzCase().id())
                .contains("seed: 20260101");
    }

    @Test
    void acceptingAValueTheDocumentForbidsIsAlsoAFinding() {
        // now the service takes everything, which is the quieter defect: every
        // consumer generated from this document is built on a promise nobody keeps
        server.resetAll();
        server.stubFor(get(urlPathMatching("/api/v1/tasks/.*"))
                .willReturn(okJson("{\"id\":\"t-1\"}")));

        ApiFuzzReport report = fuzz.run();

        assertThat(report.findings())
                .extracting(FuzzObservation::verdict)
                .contains(FuzzVerdict.OVER_PERMISSIVE);
    }

    @Test
    void anEndpointBehindAuthProducesNoValidationVerdictsAtAll() {
        // the trap v1.1 fell into: 401 to valid data, 401 to invalid data, and
        // a page of green "the service rejected bad input" results
        server.resetAll();
        server.stubFor(get(urlPathMatching("/api/v1/tasks/.*"))
                .willReturn(aResponse().withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"unauthorized\"}")));

        ApiFuzzReport report = fuzz.run();

        assertThat(report.specs().getFirst().operations())
                .filteredOn(operation -> operation.operationId().equals("getTask"))
                .singleElement()
                .satisfies(operation -> {
                    assertThat(operation.control().outcome()).isEqualTo(ControlOutcome.BLOCKED);
                    assertThat(operation.cases()).isZero();
                    assertThat(operation.skipReason()).contains("control request not accepted");
                });

        assertThat(report.findings())
                .describedAs("nothing about validation can be claimed when the door is locked")
                .isEmpty();
    }

    @Test
    void oneControlRequestPerOperationNotOnePerCase() {
        fuzz.run();

        // the baseline id is eight characters, the declared maximum; every
        // longer request is a case, and exactly one control was sent
        server.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/tasks/testforg")));
    }

    @Test
    void neverTouchesTheWriteEndpointTheDocumentDeclares() {
        fuzz.run();

        server.verify(0, postRequestedFor(urlPathEqualTo("/api/v1/tasks")));
    }
}
