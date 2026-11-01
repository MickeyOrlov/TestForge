package io.testforge.example;

import static org.assertj.core.api.Assertions.*;

import io.testforge.security.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.*;

class SecurityArtifactExampleTest {
    @Test
    void anAlreadyUsedRuntimeCredentialIsDetectedWithoutFetchingAnotherToken(@TempDir Path dir)
            throws Exception {
        var server =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                e -> {
                    byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    e.getResponseHeaders().add("Content-Type", "application/json");
                    e.sendResponseHeaders(200, body.length);
                    e.getResponseBody().write(body);
                    e.close();
                });
        server.start();
        String runtime = java.util.UUID.randomUUID().toString();
        try {
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    io.testforge.http.TestForgeHttpAutoConfiguration.class,
                                    TestForgeSecurityAutoConfiguration.class))
                    .withBean(
                            io.testforge.http.ApiRequestCustomizer.class,
                            () ->
                                    (request, service) ->
                                            request.header("Authorization", "Bearer " + runtime))
                    .withPropertyValues(
                            "forge.http.base-url=http://127.0.0.1:" + server.getAddress().getPort(),
                            "forge.security.artifacts-scan.enabled=true",
                            "forge.security.artifacts-scan.roots[0]=" + dir)
                    .run(
                            c -> {
                                c.getBean(io.testforge.http.ApiClient.class)
                                        .request()
                                        .get("/")
                                        .then()
                                        .statusCode(200);
                                Files.writeString(dir.resolve("leak.log"), runtime);
                                assertThatThrownBy(
                                                c.getBean(SecurityArtifactScanner.class)
                                                        ::assertClean)
                                        .isInstanceOf(AssertionError.class)
                                        .hasMessageNotContaining(runtime);
                                assertThat(c.getBean(SecurityCollector.class).findings().toString())
                                        .doesNotContain(runtime);
                            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aPreUploadCheckBlocksAPlantedSecretAndReportsOnlyItsLocation(@TempDir Path dir)
            throws Exception {
        Files.writeString(dir.resolve("http.log"), "password=synthetic-unmasked-value");
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                io.testforge.http.TestForgeHttpAutoConfiguration.class,
                                TestForgeSecurityAutoConfiguration.class))
                .withPropertyValues(
                        "forge.security.artifacts-scan.enabled=true",
                        "forge.security.artifacts-scan.roots[0]=" + dir,
                        "forge.security.output-dir=" + dir.resolve("reports"))
                .run(
                        c -> {
                            var scanner = c.getBean(SecurityArtifactScanner.class);
                            assertThatThrownBy(scanner::assertClean)
                                    .isInstanceOf(AssertionError.class);
                            var runner = c.getBean(SecurityRunner.class);
                            var report = runner.run();
                            assertThat(report.enabled()).isTrue();
                            assertThat(report.findings())
                                    .extracting(SecurityFinding::check)
                                    .contains("artifact-secret");
                            assertThat(
                                            Files.readString(
                                                    dir.resolve("reports/security-findings.json")))
                                    .doesNotContain("synthetic-unmasked-value");
                            assertThatThrownBy(runner::assertAcceptable)
                                    .isInstanceOf(AssertionError.class);
                        });
    }
}
