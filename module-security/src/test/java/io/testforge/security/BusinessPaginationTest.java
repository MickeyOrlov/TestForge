package io.testforge.security;

import static io.testforge.security.BusinessInvariant.Value.binding;
import static io.testforge.security.BusinessInvariant.Value.project;
import static io.testforge.security.BusinessInvariant.Value.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.TextNode;
import com.sun.net.httpserver.HttpServer;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.QueryParameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.testforge.api.explorer.PreparedRequest;
import io.testforge.auth.TestForgeAuthAutoConfiguration;
import io.testforge.http.TestForgeHttpAutoConfiguration;

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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

class BusinessPaginationTest {
    @TempDir Path dir;

    enum Mode {
        SECURE,
        LEAK,
        LOOP,
        LEAK_LOOP,
        URL,
        MISSING_NEXT,
        EMPTY,
        PAGE_LIMIT,
        ITEM_LIMIT
    }

    OpenAPI document() {
        var row =
                new ObjectSchema()
                        .addProperty("id", new StringSchema())
                        .addProperty("tenantId", new StringSchema());
        row.setAdditionalProperties(false);
        var page =
                new ObjectSchema()
                        .addProperty("items", new ArraySchema().items(row))
                        .addProperty("next", new StringSchema().nullable(true));
        page.setAdditionalProperties(false);
        return new OpenAPI()
                .openapi("3.0.3")
                .paths(
                        new Paths()
                                .addPathItem(
                                        "/catalog",
                                        new PathItem()
                                                .addParametersItem(
                                                        new QueryParameter()
                                                                .name("cursor")
                                                                .schema(new StringSchema()))
                                                .addParametersItem(
                                                        new QueryParameter()
                                                                .name("q")
                                                                .schema(new StringSchema()))
                                                .get(
                                                        new Operation()
                                                                .responses(
                                                                        new ApiResponses()
                                                                                .addApiResponse(
                                                                                        "200",
                                                                                        new ApiResponse()
                                                                                                .content(
                                                                                                        new Content()
                                                                                                                .addMediaType(
                                                                                                                        "application/json",
                                                                                                                        new MediaType()
                                                                                                                                .schema(
                                                                                                                                        page))))))));
    }

    BusinessSecuritySuite suite(Mode mode) {
        var control =
                new BusinessSecuritySuite.Step(
                        "membership",
                        BusinessSecuritySuite.Phase.CONTROL,
                        "owner",
                        new PreparedRequest("GET", "/catalog", Map.of(), Map.of()),
                        Set.of(200),
                        List.of(),
                        Map.of(),
                        List.of(
                                new BusinessInvariant(
                                        "owned-item",
                                        BusinessInvariant.Compare.CONTAINS,
                                        project(response("/items"), "/id"),
                                        binding("owned"))),
                        new BusinessPagination("/items", "/next", "cursor", 3, 10, true));
        var probe =
                new BusinessSecuritySuite.Step(
                        "filtered-list",
                        BusinessSecuritySuite.Phase.PROBE,
                        "owner",
                        new PreparedRequest("GET", "/catalog", Map.of(), Map.of("q", "orders")),
                        Set.of(200),
                        List.of(),
                        Map.of(),
                        List.of(
                                new BusinessInvariant(
                                        "only-own-tenant",
                                        BusinessInvariant.Compare.ALL_EQUAL,
                                        project(response("/items"), "/tenantId"),
                                        binding("tenant"))),
                        new BusinessPagination(
                                "/items",
                                "/next",
                                "cursor",
                                mode == Mode.PAGE_LIMIT ? 1 : 3,
                                mode == Mode.ITEM_LIMIT ? 1 : 10,
                                true));
        var test =
                new BusinessSecuritySuite.Case(
                        "list-isolation",
                        BusinessSecuritySuite.Kind.COLLECTION_ACCESS,
                        "GET",
                        "/catalog",
                        "owner",
                        List.of(control, probe),
                        List.of());
        return new BusinessSecuritySuite(
                "catalog-policy",
                "v1",
                List.of("owner"),
                Set.of(test.kind()),
                List.of(test),
                List.of());
    }

    @Test
    void membershipMayBeOnALaterPageAndSearchChecksEveryTenant() throws Exception {
        exercise(Mode.SECURE, null);
    }

    @Test
    void aForeignRowOnTheSecondPageIsAValueFreeFinding() throws Exception {
        exercise(Mode.LEAK, null);
    }

    @Test
    void loopsUrlsMissingTerminationAndLimitsCannotPass() throws Exception {
        for (Mode mode :
                List.of(
                        Mode.LOOP,
                        Mode.URL,
                        Mode.MISSING_NEXT,
                        Mode.EMPTY,
                        Mode.PAGE_LIMIT,
                        Mode.ITEM_LIMIT)) exercise(mode, null);
    }

    @Test
    void observedForeignRowsSurviveAnIncompleteLaterScan() throws Exception {
        exercise(Mode.LEAK_LOOP, null);
    }

    @Test
    void worstCasePageBudgetsAreCheckedBeforeTraffic() throws Exception {
        exercise(Mode.SECURE, "budget");
    }

    @Test
    void cursorCannotOverwriteQueryCredentials() throws Exception {
        exercise(Mode.SECURE, "credential");
    }

    private void exercise(Mode mode, String override) throws Exception {
        String owned = UUID.randomUUID().toString(),
                tenant = UUID.randomUUID().toString(),
                secret = "synthetic-" + UUID.randomUUID();
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/catalog",
                e -> {
                    calls.incrementAndGet();
                    String query = e.getRequestURI().getRawQuery();
                    boolean probe = query != null && query.contains("q=");
                    boolean second = query != null && query.contains("cursor=two");
                    boolean leak = probe && second && (mode == Mode.LEAK || mode == Mode.LEAK_LOOP);
                    String body =
                            "{\"items\":[{\"id\":\""
                                    + (second ? owned : "first")
                                    + "\",\"tenantId\":\""
                                    + (leak ? "foreign-tenant" : tenant)
                                    + "\"}],\"next\":"
                                    + (second
                                                    && !(probe
                                                            && (mode == Mode.LOOP
                                                                    || mode == Mode.LEAK_LOOP))
                                            ? "null"
                                            : "\"two\"")
                                    + "}";
                    if (probe && mode == Mode.URL)
                        body = "{\"items\":[],\"next\":\"https://ignored.example.invalid/steal\"}";
                    if (probe && mode == Mode.MISSING_NEXT) body = "{\"items\":[]}";
                    if (probe && mode == Mode.EMPTY) body = "{\"items\":[],\"next\":null}";
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    e.getResponseHeaders().add("Content-Type", "application/json");
                    e.sendResponseHeaders(200, bytes.length);
                    e.getResponseBody().write(bytes);
                    e.close();
                });
        server.start();
        Path run = dir.resolve(mode.name() + (override == null ? "" : override));
        try {
            var context =
                    new ApplicationContextRunner()
                            .withConfiguration(
                                    AutoConfigurations.of(
                                            TestForgeHttpAutoConfiguration.class,
                                            TestForgeAuthAutoConfiguration.class,
                                            TestForgeSecurityAutoConfiguration.class))
                            .withPropertyValues(
                                    "forge.http.base-url=http://127.0.0.1:"
                                            + server.getAddress().getPort(),
                                    "forge.auth.enabled=true",
                                    "forge.auth.roles.owner.type=static",
                                    "forge.auth.roles.owner.token=" + secret,
                                    "forge.security.business.enabled=true",
                                    "forge.security.business.test-environment=true",
                                    "forge.security.business.allowed-hosts[0]=127.0.0.1",
                                    "forge.security.output-dir=" + run.resolve("reports"),
                                    "forge.security.business.policy.required=" + (override == null),
                                    "forge.security.business.policy.review-dir="
                                            + run.resolve("reviews"));
            if ("budget".equals(override))
                context = context.withPropertyValues("forge.security.business.max-requests=3");
            if ("credential".equals(override))
                context =
                        context.withPropertyValues(
                                "forge.auth.roles.owner.type=api-key",
                                "forge.auth.roles.owner.api-key.name=cursor",
                                "forge.auth.roles.owner.api-key.in=query");
            context.run(
                    c -> {
                        assertThat(c).hasNotFailed();
                        var doc = document();
                        var policy = suite(mode);
                        if (override != null) {
                            assertThatThrownBy(
                                            () ->
                                                    c.getBean(BusinessSecurityRunner.class)
                                                            .run(
                                                                    doc,
                                                                    policy,
                                                                    Map.of(
                                                                            "owned",
                                                                            TextNode.valueOf(owned),
                                                                            "tenant",
                                                                            TextNode.valueOf(
                                                                                    tenant))))
                                    .isInstanceOf(BusinessSecurityException.class);
                            assertThat(calls).hasValue(0);
                            return;
                        }
                        c.getBean(BusinessPolicyRunner.class)
                                .writeReview(doc, policy, Set.of("owned", "tenant"));
                        var measured =
                                c.getBean(BusinessSecurityRunner.class)
                                        .run(
                                                doc,
                                                policy,
                                                Map.of(
                                                        "owned",
                                                        TextNode.valueOf(owned),
                                                        "tenant",
                                                        TextNode.valueOf(tenant)));
                        var gate = c.getBean(SecurityRunner.class);
                        if (mode == Mode.SECURE) {
                            assertThat(measured.complete()).isTrue();
                            assertThat(calls).hasValue(4);
                            assertThat(gate.assertAcceptable().healthy()).isTrue();
                        } else {
                            assertThat(measured.complete()).isEqualTo(mode == Mode.LEAK);
                            assertThatThrownBy(gate::assertAcceptable)
                                    .isInstanceOf(AssertionError.class);
                            if (mode == Mode.LEAK || mode == Mode.LEAK_LOOP)
                                assertThat(gate.run().findings())
                                        .anyMatch(
                                                f ->
                                                        f.target()
                                                                .endsWith(
                                                                        "invariant"
                                                                            + " only-own-tenant"));
                            else assertThat(gate.run().findings()).isEmpty();
                            assertThat(calls.get()).isLessThanOrEqualTo(4);
                        }
                        try (var files = Files.walk(run)) {
                            for (Path file : files.filter(Files::isRegularFile).toList())
                                assertThat(Files.readString(file))
                                        .doesNotContain(
                                                owned, tenant, secret, "ignored.example.invalid");
                        }
                    });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void changingPaginationLimitsRequiresPolicyReview() {
        var runner =
                new BusinessPolicyRunner(
                        new BusinessPolicyProperties(
                                true, dir.resolve("review").toString(), null, null, null, null),
                        new BusinessSecurityProperties(
                                null, null, null, null, null, null, null, null, null, null, null),
                        new SecurityProperties(
                                null, dir.resolve("report").toString(), null, null, null, null),
                        Set.of("owner"),
                        new SecurityCollector(100),
                        io.testforge.artifact.ArtifactSink.NO_OP);
        var approved =
                runner.writeReview(document(), suite(Mode.SECURE), Set.of("owned", "tenant"));
        assertThat(
                        runner.inspect(
                                        document(),
                                        suite(Mode.PAGE_LIMIT),
                                        approved,
                                        Set.of("owned", "tenant"))
                                .status())
                .isEqualTo(BusinessPolicyReport.Status.REVIEW_REQUIRED);
    }
}
