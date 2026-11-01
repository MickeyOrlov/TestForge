package io.testforge.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.testforge.api.explorer.PreparedRequest;
import io.testforge.http.TestForgeHttpAutoConfiguration;
import io.testforge.security.BusinessSecurityRunner;
import io.testforge.security.BusinessSecuritySuite;
import io.testforge.security.SecurityRunner;
import io.testforge.security.TestForgeSecurityAutoConfiguration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Published JARs only: new policy records, transitive Swagger types, configuration and gate. */
class PublishedBusinessSecuritySmokeTest {
    @Test
    void aPublishedBusinessPolicyCanRunWithoutAuthOrReportingModulesEnabled(@TempDir Path dir)
            throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/public",
                exchange -> {
                    byte[] body = "{\"message\":\"sample\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            var request = new PreparedRequest("GET", "/public", Map.of(), Map.of());
            var control =
                    new BusinessSecuritySuite.Step(
                            "control",
                            BusinessSecuritySuite.Phase.CONTROL,
                            "anonymous",
                            request,
                            Set.of(200),
                            List.of(),
                            Map.of());
            var probe =
                    new BusinessSecuritySuite.Step(
                            "probe",
                            BusinessSecuritySuite.Phase.PROBE,
                            "anonymous",
                            request,
                            Set.of(200),
                            List.of(
                                    new BusinessSecuritySuite.Assertion(
                                            "/secret", BusinessSecuritySuite.Match.ABSENT, null)),
                            Map.of());
            var test =
                    new BusinessSecuritySuite.Case(
                            "no-secret",
                            BusinessSecuritySuite.Kind.PROPERTY_READ,
                            "GET",
                            "/public",
                            "anonymous",
                            List.of(control, probe),
                            List.of());
            var suite =
                    new BusinessSecuritySuite(
                            "public-policy",
                            "v1",
                            List.of("anonymous"),
                            Set.of(BusinessSecuritySuite.Kind.PROPERTY_READ),
                            List.of(test),
                            List.of());
            var document =
                    new OpenAPI()
                            .paths(
                                    new Paths()
                                            .addPathItem(
                                                    "/public",
                                                    new PathItem().get(new Operation())));
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    TestForgeHttpAutoConfiguration.class,
                                    TestForgeSecurityAutoConfiguration.class))
                    .withPropertyValues(
                            "forge.http.base-url=http://127.0.0.1:" + server.getAddress().getPort(),
                            "forge.security.business.enabled=true",
                            "forge.security.business.test-environment=true",
                            "forge.security.business.allowed-hosts[0]=127.0.0.1",
                            "forge.security.output-dir=" + dir)
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                assertThat(
                                                context.getBean(BusinessSecurityRunner.class)
                                                        .run(document, suite, Map.of())
                                                        .complete())
                                        .isTrue();
                                assertThat(
                                                context.getBean(SecurityRunner.class)
                                                        .assertAcceptable()
                                                        .healthy())
                                        .isTrue();
                                assertThat(dir.resolve("business-public-policy-v1-coverage.json"))
                                        .exists();
                            });
        } finally {
            server.stop(0);
        }
    }
}
