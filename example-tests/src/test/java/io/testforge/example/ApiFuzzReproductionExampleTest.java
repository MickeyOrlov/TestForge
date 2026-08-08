package io.testforge.example;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.testforge.api.fuzz.ApiFuzzReport;
import io.testforge.api.fuzz.ApiFuzzRunner;
import io.testforge.api.fuzz.FuzzObservation;
import io.testforge.api.fuzz.Reproducibility;
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
 * The whole point of v1.3 in one test: a finding turns into a confirmed,
 * minimized reproducer an engineer can act on.
 *
 * <p>The service here crashes whenever {@code priority} is above its declared
 * maximum, regardless of anything else in the payload — so minimization can
 * strip the optional fields away and still show the defect.
 *
 * <p>Confirmation and shrinking are enabled explicitly, including the separate
 * opt-in for repeating a write method. Neither happens by default.
 */
@SpringBootTest(properties = {
        "forge.api-fuzz.enabled=true",
        "forge.api-fuzz.seed=20260101",
        "forge.api-fuzz.methods=POST",
        "forge.api-fuzz.allow-unsafe-methods=true",
        "forge.api-fuzz.allow-unsafe-confirmation=true",
        "forge.api-fuzz.confirmation-runs=2",
        "forge.api-fuzz.max-shrink-attempts=25",
        "forge.api-fuzz.output-dir=build/api-fuzz/reproduction-example",
        "forge.api-discovery.specs.demo.location=classpath:/openapi/fuzz-demo-api.yaml"
})
class ApiFuzzReproductionExampleTest {

    private static final String CASE = "createTask/body:$.priority/ABOVE_MAXIMUM";
    private static final Path OUTPUT = Path.of("build/api-fuzz/reproduction-example");

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

        // the defect: an out-of-range priority crashes the handler, whatever
        // else the payload contains
        server.stubFor(post(urlPathEqualTo("/api/v1/tasks"))
                .atPriority(1)
                .withRequestBody(matchingJsonPath("$[?(@.priority > 5)]"))
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"internal error\"}")));
        server.stubFor(post(urlPathEqualTo("/api/v1/tasks"))
                .atPriority(10)
                .willReturn(aResponse().withStatus(201)));
    }

    @Test
    void aFindingBecomesAConfirmedMinimalReproducer() throws IOException {
        ApiFuzzReport report = fuzz.run();
        FuzzObservation finding = finding(report);

        // 1. it is real, and the run says how it knows
        assertThat(finding.confirmation().reproducibility()).isEqualTo(Reproducibility.REPRODUCIBLE);
        assertThat(finding.confirmation().summary()).isEqualTo("REPRODUCIBLE (2/2)");

        // 2. the request that shows it is as small as the schema allows
        assertThat(finding.shrink().reduced()).isTrue();
        assertThat(finding.shrink().minimalBody())
                .contains("\"priority\":6")
                .contains("\"title\"")
                .doesNotContain("\"note\"");
    }

    @Test
    void theReproductionFolderIsWrittenForTheEngineer() throws IOException {
        fuzz.run();

        Path directory = OUTPUT.resolve("reproductions").resolve("createtask-body-.priority-above_maximum");
        assertThat(directory).exists();
        assertThat(Files.readString(directory.resolve("reproduce.md")))
                .contains("## 7. Run it again")
                .contains(CASE)
                .contains("REPRODUCIBLE");
        assertThat(Files.readString(directory.resolve("request.json"))).contains("priority");
    }

    @Test
    void theReportShowsReproducibilityAndMinimizationSideBySide() throws IOException {
        ApiFuzzReport report = fuzz.run();

        assertThat(Files.readString(Path.of(report.reportMarkdown())))
                .contains("reproducibility")
                .contains("REPRODUCIBLE (2/2)")
                .contains("fields in");
    }

    private FuzzObservation finding(ApiFuzzReport report) {
        return report.findings().stream()
                .filter(observation -> observation.fuzzCase().id().equals(CASE))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected " + CASE + " to be a finding"));
    }
}
