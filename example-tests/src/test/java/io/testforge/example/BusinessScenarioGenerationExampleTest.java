package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;

import io.testforge.api.discovery.ApiSpecSource;
import io.testforge.api.discovery.OpenApiSpecParser;
import io.testforge.api.explorer.OperationSelector;
import io.testforge.auth.TestForgeAuthAutoConfiguration;
import io.testforge.core.TestForgeCoreAutoConfiguration;
import io.testforge.http.TestForgeHttpAutoConfiguration;
import io.testforge.security.BusinessScenarioGenerator;
import io.testforge.security.BusinessScenarioSeed;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** OpenAPI -> generated draft -> explicit project policy -> real role-specific execution. */
class BusinessScenarioGenerationExampleTest {
    @Test
    void reviewedGeneratedCasesCreateBindCheckAndRemoveTheirOwnOrders(@TempDir Path dir)
            throws Exception {
        exercise(dir, false);
    }

    @Test
    void theSameReviewedCasesDetectBrokenFieldAndObjectPermissions(@TempDir Path dir)
            throws Exception {
        exercise(dir, true);
    }

    private void exercise(Path dir, boolean broken) throws Exception {
        var mapper = new ObjectMapper();
        var document =
                new OpenApiSpecParser()
                        .parse(
                                new ApiSpecSource(
                                        "orders", "classpath:security/orders-linked.openapi.json"));
        Map<String, Integer> orders = new HashMap<>();
        List<String> ids = new ArrayList<>();
        var count = new AtomicInteger();
        String token = "synthetic-owner-" + UUID.randomUUID();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/orders",
                e -> {
                    count.incrementAndGet();
                    boolean owner =
                            ("Bearer " + token)
                                    .equals(e.getRequestHeaders().getFirst("Authorization"));
                    String path = e.getRequestURI().getPath();
                    String id = path.substring(path.lastIndexOf('/') + 1);
                    int status = 200;
                    String body = "{}";
                    if (e.getRequestMethod().equals("POST") && path.equals("/orders")) {
                        id = UUID.randomUUID().toString();
                        ids.add(id);
                        orders.put(id, 50);
                        status = 201;
                        body = "{\"id\":\"" + id + "\",\"price\":50}";
                    } else if (!orders.containsKey(id)) status = 404;
                    else
                        switch (e.getRequestMethod()) {
                            case "GET" -> {
                                if (!owner && !broken) status = 403;
                                else
                                    body =
                                            "{\"id\":\""
                                                    + id
                                                    + "\",\"price\":"
                                                    + orders.get(id)
                                                    + "}";
                            }
                            case "PATCH" -> {
                                status = 403;
                                if (broken) orders.put(id, 0);
                            }
                            case "DELETE" -> {
                                orders.remove(id);
                                status = 204;
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
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    TestForgeCoreAutoConfiguration.class,
                                    TestForgeHttpAutoConfiguration.class,
                                    TestForgeAuthAutoConfiguration.class,
                                    TestForgeSecurityAutoConfiguration.class))
                    .withPropertyValues(
                            "forge.http.base-url=http://127.0.0.1:" + server.getAddress().getPort(),
                            "forge.security.output-dir=" + dir,
                            "forge.security.business.enabled=true",
                            "forge.security.business.test-environment=true",
                            "forge.security.business.allowed-hosts[0]=127.0.0.1",
                            "forge.security.business.write-checks=true",
                            "forge.security.business.allow-unsafe-methods=true",
                            "forge.auth.enabled=true",
                            "forge.auth.roles.owner.type=static",
                            "forge.auth.roles.owner.token=" + token,
                            "forge.auth.roles.other.type=static",
                            "forge.auth.roles.other.token=synthetic-other")
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                var seeds =
                                        List.of(
                                                new BusinessScenarioSeed(
                                                        "generated-fields",
                                                        "v1",
                                                        BusinessSecuritySuite.Kind.PROPERTY_WRITE,
                                                        "POST /orders",
                                                        "PATCH /orders/{id}",
                                                        "owner",
                                                        "owner",
                                                        List.of(),
                                                        "DELETE /orders/{id}"),
                                                new BusinessScenarioSeed(
                                                        "generated-object",
                                                        "v1",
                                                        BusinessSecuritySuite.Kind.OBJECT_ACCESS,
                                                        "POST /orders",
                                                        "GET /orders/{id}",
                                                        "owner",
                                                        "other",
                                                        List.of(),
                                                        "DELETE /orders/{id}"));
                                var generated =
                                        context.getBean(BusinessScenarioGenerator.class)
                                                .generate(document, seeds);
                                assertThat(count).hasValue(0);
                                for (var draft : generated.drafts()) {
                                    assertThat(draft.status()).isEqualTo("needs_review");
                                    assertThatThrownBy(
                                                    () ->
                                                            mapper.treeToValue(
                                                                    draft.suite(),
                                                                    BusinessSecuritySuite.class))
                                            .isInstanceOf(Exception.class);
                                    ObjectNode reviewed = draft.suite().deepCopy();
                                    // Project review supplies rights and invariants; they are not
                                    // extracted from documentation.
                                    for (JsonNode step : reviewed.at("/cases/0/steps")) {
                                        var statuses = ((ObjectNode) step).putArray("statuses");
                                        statuses.add(
                                                step.path("phase").asText().equals("PROBE")
                                                        ? 403
                                                        : step.path("request")
                                                                        .path("method")
                                                                        .asText()
                                                                        .equals("POST")
                                                                ? 201
                                                                : 200);
                                        if (step.path("phase").asText().equals("VERIFY"))
                                            ((ObjectNode) step)
                                                    .putArray("assertions")
                                                    .addObject()
                                                    .put("pointer", "")
                                                    .put("match", "EQUALS")
                                                    .put("expected", "${before}");
                                    }
                                    for (JsonNode step : reviewed.at("/cases/0/cleanup"))
                                        ((ObjectNode) step).putArray("statuses").add(204);
                                    var exclusions = reviewed.putArray("exclusions");
                                    var test = reviewed.at("/cases/0");
                                    for (var operation :
                                            new OperationSelector().select("orders", document))
                                        if (!(operation
                                                        .method()
                                                        .equals(test.path("method").asText())
                                                && operation
                                                        .pathTemplate()
                                                        .equals(test.path("path").asText())))
                                            exclusions
                                                    .addObject()
                                                    .put("kind", test.path("kind").asText())
                                                    .put("method", operation.method())
                                                    .put("path", operation.pathTemplate())
                                                    .put("role", test.path("role").asText())
                                                    .put("reason", "demo-other-operations");
                                    var suite =
                                            mapper.treeToValue(
                                                    reviewed, BusinessSecuritySuite.class);
                                    Map<String, JsonNode> inputs = new HashMap<>();
                                    for (var need : draft.needs())
                                        if (need.kind().equals("input")) {
                                            assertThat(need.binding()).endsWith("_body");
                                            inputs.put(
                                                    need.binding(),
                                                    mapper.createObjectNode()
                                                            .put(
                                                                    "price",
                                                                    need.binding().contains("step0")
                                                                            ? 50
                                                                            : 0));
                                        }
                                    assertThat(
                                                    context.getBean(BusinessSecurityRunner.class)
                                                            .run(document, suite, inputs)
                                                            .complete())
                                            .isTrue();
                                }
                                assertThat(orders).isEmpty();
                                assertThat(ids).hasSize(2);
                                var gate = context.getBean(SecurityRunner.class);
                                if (broken) {
                                    assertThat(gate.run().findings()).hasSize(2);
                                    assertThatThrownBy(gate::assertAcceptable)
                                            .isInstanceOf(AssertionError.class);
                                } else assertThat(gate.assertAcceptable().healthy()).isTrue();
                                try (var files = Files.walk(dir)) {
                                    for (Path file : files.filter(Files::isRegularFile).toList()) {
                                        String artifact = Files.readString(file);
                                        assertThat(artifact).doesNotContain(token);
                                        for (String observedId : ids)
                                            assertThat(artifact).doesNotContain(observedId);
                                    }
                                }
                            });
        } finally {
            server.stop(0);
        }
    }
}
