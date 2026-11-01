package io.testforge.example;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import io.testforge.core.TestForgeCoreAutoConfiguration;
import io.testforge.http.*;
import io.testforge.reporting.TestForgeReportingAutoConfiguration;
import io.testforge.security.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.*;

class SecurityPassiveExampleTest {
    @Test
    void existingResponsesProduceFindingsAndReportsWithoutExtraRequests() throws Exception {
        var server =
                new WireMockServer(
                        WireMockConfiguration.options().dynamicPort().bindAddress("127.0.0.1"));
        server.start();
        try {
            String token =
                    "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJzeW50aGV0aWMifQ.c3ludGhldGljLXNpZ25hdHVyZQ";
            server.stubFor(
                    get(urlEqualTo("/security-example"))
                            .atPriority(10)
                            .willReturn(
                                    okJson("{\"token\":\"" + token + "\"}")
                                            .withHeader("Access-Control-Allow-Origin", "*")
                                            .withHeader(
                                                    "Access-Control-Allow-Credentials", "true")));
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    TestForgeCoreAutoConfiguration.class,
                                    TestForgeHttpAutoConfiguration.class,
                                    TestForgeSecurityAutoConfiguration.class,
                                    TestForgeReportingAutoConfiguration.class))
                    .withPropertyValues(
                            "forge.http.base-url=" + server.baseUrl(),
                            "forge.security.passive.enabled=true",
                            "forge.security.output-dir=build/security-example",
                            "forge.reporting.artifacts.enabled=true",
                            "forge.reporting.artifacts.dir=build/security-example-index",
                            "forge.reporting.artifacts.run-id=passive")
                    .run(
                            c -> {
                                c.getBean(ApiClient.class)
                                        .request()
                                        .get("/security-example")
                                        .then()
                                        .statusCode(200);
                                var runner = c.getBean(SecurityRunner.class);
                                var report = runner.run();
                                assertThat(report.findings())
                                        .extracting(SecurityFinding::check)
                                        .contains(
                                                "missing-nosniff",
                                                "cors-credentials-wildcard",
                                                "token-exposure");
                                assertThatThrownBy(runner::assertAcceptable)
                                        .isInstanceOf(AssertionError.class);
                                for (String name :
                                        java.util.List.of(
                                                "security-findings.json",
                                                "security.md",
                                                "security.sarif"))
                                    assertThat(
                                                    Files.readString(
                                                            Path.of(
                                                                    "build/security-example",
                                                                    name)))
                                            .doesNotContain(token);
                            });
            server.verify(1, getRequestedFor(urlEqualTo("/security-example")));
            assertThat(
                            Files.readString(
                                    Path.of("build/security-example-index/passive/manifest.json")))
                    .contains("module-security", "security-findings.json", "healthy");
        } finally {
            server.stop();
        }
    }
}
