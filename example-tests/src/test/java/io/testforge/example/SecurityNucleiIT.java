package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;

import io.testforge.security.SecurityNucleiRunner;
import io.testforge.security.SecurityRunner;
import io.testforge.security.TestForgeSecurityAutoConfiguration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Tag("containers")
class SecurityNucleiIT {
    @Test
    void pinnedTemplateReportsMissingHeadersAndAcceptsTheCompleteFixture() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new CopyOnWriteArrayList<String>();
        server.createContext(
                "/",
                exchange -> {
                    String path = exchange.getRequestURI().toString();
                    requests.add(exchange.getRequestMethod() + " " + path);
                    var headers = exchange.getResponseHeaders();
                    headers.set("Content-Type", "text/html; charset=utf-8");
                    headers.set("Set-Cookie", "fixture-cookie=do-not-record");
                    if (path.equals("/complete")) {
                        headers.set("Strict-Transport-Security", "max-age=3600");
                        headers.set("Content-Security-Policy", "default-src 'none'");
                        headers.set("Permissions-Policy", "geolocation=()");
                        headers.set("X-Frame-Options", "DENY");
                        headers.set("X-Content-Type-Options", "nosniff");
                        headers.set("X-Permitted-Cross-Domain-Policies", "none");
                        headers.set("Referrer-Policy", "no-referrer");
                        headers.set("Cross-Origin-Embedder-Policy", "require-corp");
                        headers.set("Cross-Origin-Opener-Policy", "same-origin");
                        headers.set("Cross-Origin-Resource-Policy", "same-origin");
                    }
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                });
        server.start();
        try {
            int port = server.getAddress().getPort();
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(TestForgeSecurityAutoConfiguration.class))
                    .withPropertyValues(
                            "forge.security.nuclei.enabled=true",
                            "forge.security.nuclei.test-environment=true",
                            "forge.security.nuclei.allowed-hosts[0]=127.0.0.1:" + port,
                            "forge.security.fail-on[0]=info",
                            "forge.security.output-dir=build/security-nuclei-it")
                    .run(
                            context -> {
                                context.getBean(SecurityNucleiRunner.class)
                                        .scanPages(
                                                List.of("/sample", "/complete"),
                                                "http://127.0.0.1:" + port);
                                var security = context.getBean(SecurityRunner.class);
                                var report = security.run();
                                assertThatThrownBy(security::assertAcceptable)
                                        .hasMessageContaining("fail-on severities");
                                assertThat(report.coverage())
                                        .hasSize(2)
                                        .allMatch(row -> row.status().equals("measured"));
                                assertThat(report.findings())
                                        .isNotEmpty()
                                        .allMatch(
                                                finding ->
                                                        finding.target()
                                                                .startsWith("GET /sample "));
                                assertThat(report.findings())
                                        .anyMatch(
                                                finding ->
                                                        finding.target()
                                                                .equals(
                                                                        "GET /sample"
                                                                            + " content-security-policy"));
                                assertThat(report.toString())
                                        .doesNotContain("do-not-record", "security-scope-");
                                assertThat(requests)
                                        .containsExactlyInAnyOrder("GET /sample", "GET /complete");
                            });
        } finally {
            server.stop(0);
        }
    }
}
