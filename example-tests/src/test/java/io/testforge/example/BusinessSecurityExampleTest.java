package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.testforge.auth.TestForgeAuthAutoConfiguration;
import io.testforge.core.TestForgeCoreAutoConfiguration;
import io.testforge.http.TestForgeHttpAutoConfiguration;
import io.testforge.security.BusinessSecurityRunner;
import io.testforge.security.BusinessSecuritySuite;
import io.testforge.security.SecurityCollector;
import io.testforge.security.SecurityRunner;
import io.testforge.security.TestForgeSecurityAutoConfiguration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Three personas: owner and another user in tenant A, plus a user in tenant B. */
class BusinessSecurityExampleTest {
    @Test
    void projectPolicyChecksFieldsForeignUsersAndTenants(@TempDir Path dir) throws Exception {
        exercise(dir, false);
    }

    @Test
    void anApiThatDeniesButMutatesCannotPassTheGate(@TempDir Path dir) throws Exception {
        exercise(dir, true);
    }

    private void exercise(Path dir, boolean broken) throws Exception {
        var mapper = new ObjectMapper();
        var price = new AtomicInteger(50);
        var deletes = new AtomicInteger();
        String owner = "synthetic-owner-" + UUID.randomUUID();
        String foreign = "synthetic-other-" + UUID.randomUUID();
        String tenant = "synthetic-tenant-" + UUID.randomUUID();
        String orderId = UUID.randomUUID().toString();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/orders",
                e -> {
                    int status = 200;
                    String body = "{}";
                    boolean owned =
                            ("Bearer " + owner)
                                    .equals(e.getRequestHeaders().getFirst("Authorization"));
                    switch (e.getRequestMethod()) {
                        case "GET" -> {
                            if (!owned) status = 403;
                            else
                                body =
                                        "{\"id\":\""
                                                + orderId
                                                + "\",\"ownerId\":\"owner-a\",\"tenantId\":\"tenant-a\",\"price\":"
                                                + price.get()
                                                + "}";
                        }
                        case "PATCH" -> {
                            status = 403;
                            if (broken) price.set(0);
                        }
                        case "DELETE" -> {
                            status = 204;
                            deletes.incrementAndGet();
                        }
                        default -> status = 405;
                    }
                    e.getRequestBody().readAllBytes();
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    e.getResponseHeaders().add("Content-Type", "application/json");
                    e.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
                    if (status != 204) e.getResponseBody().write(bytes);
                    e.close();
                });
        server.start();
        try {
            var document =
                    new OpenAPI()
                            .paths(
                                    new Paths()
                                            .addPathItem(
                                                    "/orders/{id}",
                                                    new PathItem()
                                                            .get(new Operation())
                                                            .patch(new Operation())
                                                            .delete(new Operation())));
            var suites =
                    mapper.readValue(
                            getClass().getResourceAsStream("/security/orders-business-policy.json"),
                            BusinessSecuritySuite[].class);
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    TestForgeCoreAutoConfiguration.class,
                                            TestForgeHttpAutoConfiguration.class,
                                    TestForgeAuthAutoConfiguration.class,
                                            TestForgeSecurityAutoConfiguration.class))
                    .withPropertyValues(
                            "forge.http.base-url=http://127.0.0.1:" + server.getAddress().getPort(),
                            "forge.security.business.enabled=true",
                            "forge.security.business.test-environment=true",
                            "forge.security.business.allowed-hosts[0]=127.0.0.1",
                            "forge.security.business.write-checks=true",
                            "forge.security.business.allow-unsafe-methods=true",
                            "forge.security.output-dir=" + dir,
                            "forge.auth.enabled=true",
                            "forge.auth.default-role=owner",
                            "forge.auth.roles.owner.type=static",
                            "forge.auth.roles.owner.token=" + owner,
                            "forge.auth.roles.other.type=static",
                            "forge.auth.roles.other.token=" + foreign,
                            "forge.auth.roles.tenant.type=static",
                            "forge.auth.roles.tenant.token=" + tenant)
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                var runner = context.getBean(BusinessSecurityRunner.class);
                                for (var suite : suites) {
                                    // Project fixture preparation; module-state/module-flow can
                                    // supply this in a real project.
                                    price.set(50);
                                    var report =
                                            runner.run(
                                                    document,
                                                    suite,
                                                    Map.of(
                                                            "order",
                                                                    mapper.getNodeFactory()
                                                                            .textNode(orderId),
                                                            "foreignOwner",
                                                                    mapper.getNodeFactory()
                                                                            .textNode("owner-b")));
                                    assertThat(report.complete()).isTrue();
                                }
                                var gate = context.getBean(SecurityRunner.class);
                                if (broken) {
                                    assertThat(context.getBean(SecurityCollector.class).findings())
                                            .anyMatch(
                                                    f ->
                                                            f.check()
                                                                    .equals(
                                                                            "business-property-write"));
                                    assertThatThrownBy(gate::assertAcceptable)
                                            .isInstanceOf(AssertionError.class);
                                } else assertThat(gate.assertAcceptable().healthy()).isTrue();
                                assertThat(deletes).hasValue(3);
                                for (String file :
                                        List.of(
                                                "security-findings.json",
                                                "security.md",
                                                "security.sarif"))
                                    assertThat(Files.readString(dir.resolve(file)))
                                            .doesNotContain(owner, foreign, tenant, orderId);
                            });
        } finally {
            server.stop(0);
        }
    }
}
