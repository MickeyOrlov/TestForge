package io.testforge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.testforge.api.explorer.PreparedRequest;
import io.testforge.artifact.ArtifactSink;
import io.testforge.auth.AccessToken;
import io.testforge.auth.CredentialPlacement;
import io.testforge.auth.TokenProvider;
import io.testforge.auth.TokenSource;
import io.testforge.http.HttpProperties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

class BusinessSecurityTest {
    @TempDir Path dir;
    final ObjectMapper mapper = new ObjectMapper();
    HttpServer server;
    final AtomicInteger requests = new AtomicInteger();
    final AtomicInteger cleanup = new AtomicInteger();
    boolean broken;
    boolean stallBody;
    final java.util.List<com.sun.net.httpserver.HttpExchange> stalled =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    boolean controlFails;
    boolean cleanupFails;
    final java.util.concurrent.atomic.AtomicReference<String> patchBody =
            new java.util.concurrent.atomic.AtomicReference<>();
    boolean redirect;
    boolean oversize;
    int price = 50;
    int refunds;
    String state = "paid";
    String credential = "synthetic-credential-" + java.util.UUID.randomUUID();
    String privateValue = "synthetic-private-" + java.util.UUID.randomUUID();
    final SecurityCollector collector = new SecurityCollector(1000);
    BusinessSecurityRunner runner;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/orders",
                e -> {
                    requests.incrementAndGet();
                    if (stallBody) {
                        e.sendResponseHeaders(200, 100);
                        stalled.add(e);
                        return;
                    }
                    String role = e.getRequestHeaders().getFirst("Authorization");
                    String method = e.getRequestMethod();
                    String path = e.getRequestURI().getPath();
                    int status = 200;
                    String body = "{}";
                    if (method.equals("DELETE")) {
                        cleanup.incrementAndGet();
                        status = cleanupFails ? 503 : 204;
                    } else if (redirect) {
                        status = 302;
                        e.getResponseHeaders().add("Location", "/outside");
                    } else if (controlFails && (credential + "-owner").equals(role)) status = 500;
                    else if (method.equals("GET")) {
                        if (!broken
                                && !Set.of(credential + "-owner", credential + "-admin")
                                        .contains(role == null ? "" : role)) status = 403;
                        else {
                            body =
                                    "{\"id\":\"fixture\",\"price\":"
                                            + price
                                            + ",\"state\":\""
                                            + state
                                            + "\",\"refunds\":"
                                            + refunds;
                            if (broken || (credential + "-admin").equals(role))
                                body += ",\"privateNote\":\"" + privateValue + "\"";
                            body += "}";
                            if (oversize) body = "{\"oversize\":\"" + "x".repeat(2048) + "\"}";
                        }
                    } else if (method.equals("PATCH")) {
                        // Even the broken service says 403: persisted-state verification must catch
                        // it.
                        if (broken) price = 0;
                        status = 403;
                    } else if (path.endsWith("/cancel")) {
                        status = broken ? 200 : 409;
                        if (broken) state = "cancelled";
                    } else if (path.endsWith("/refund")) {
                        if (refunds == 0) {
                            refunds++;
                            state = "refunded";
                        } else {
                            status = 409;
                            if (broken) refunds++;
                        }
                    }
                    String submitted =
                            new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    if (method.equals("PATCH")) patchBody.set(submitted);
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    e.getResponseHeaders().add("Content-Type", "application/json");
                    e.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
                    if (status != 204) e.getResponseBody().write(bytes);
                    e.close();
                });
        server.createContext(
                "/outside",
                e -> {
                    throw new AssertionError("redirect was followed");
                });
        server.start();
        runner = runner(properties(null, null, null, null));
    }

    @AfterEach
    void stop() {
        if (runner != null) runner.close();
        stalled.forEach(com.sun.net.httpserver.HttpExchange::close);
        server.stop(0);
    }

    BusinessSecurityProperties properties(
            Boolean writes, Integer bodyLimit, Integer budget, Duration timeout) {
        return new BusinessSecurityProperties(
                true,
                true,
                List.of("127.0.0.1"),
                writes == null || writes,
                true,
                budget,
                null,
                null,
                null,
                bodyLimit,
                timeout);
    }

    BusinessSecurityRunner runner(BusinessSecurityProperties properties) {
        Map<String, TokenSource> sources = new HashMap<>();
        Map<String, CredentialPlacement> placements = new HashMap<>();
        for (String role : List.of("owner", "other", "tenant", "admin")) {
            sources.put(role, () -> new AccessToken(credential + "-" + role, null));
            placements.put(role, CredentialPlacement.header("Authorization", ""));
        }
        var tokens = new TokenProvider(sources, placements, Clock.systemUTC(), Duration.ZERO);
        var http =
                new HttpProperties(
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        Map.of("Authorization", credential + "-admin"),
                        null,
                        null,
                        Duration.ofSeconds(2),
                        null,
                        null,
                        null,
                        null,
                        null);
        return new BusinessSecurityRunner(
                properties,
                http,
                tokens,
                null,
                new SecuritySecretRegistry(),
                new SecurityProperties(null, dir.toString(), null, null, null, null),
                collector,
                ArtifactSink.NO_OP);
    }

    OpenAPI document() {
        return new OpenAPI()
                .paths(
                        new Paths()
                                .addPathItem(
                                        "/orders/{id}",
                                        new PathItem()
                                                .get(new Operation())
                                                .patch(new Operation())
                                                .delete(new Operation()))
                                .addPathItem(
                                        "/orders/{id}/cancel", new PathItem().post(new Operation()))
                                .addPathItem(
                                        "/orders/{id}/refund",
                                        new PathItem().post(new Operation())));
    }

    PreparedRequest request(String method, String path, String body) {
        return new PreparedRequest(
                method,
                path,
                Map.of("id", "${order}"),
                Map.of(),
                body,
                body == null ? null : "application/json");
    }

    BusinessSecuritySuite.Step step(
            String id,
            BusinessSecuritySuite.Phase phase,
            String role,
            String method,
            String path,
            int status,
            List<BusinessSecuritySuite.Assertion> assertions,
            Map<String, String> captures) {
        return new BusinessSecuritySuite.Step(
                id,
                phase,
                role,
                request(method, path, method.equals("PATCH") ? "{\"price\":0}" : null),
                Set.of(status),
                assertions,
                captures);
    }

    BusinessSecuritySuite.Assertion equals(String pointer, String value) {
        return new BusinessSecuritySuite.Assertion(
                pointer,
                BusinessSecuritySuite.Match.EQUALS,
                mapper.getNodeFactory().textNode(value));
    }

    BusinessSecuritySuite.Case readCase(BusinessSecuritySuite.Kind kind, String role) {
        var assertions =
                kind == BusinessSecuritySuite.Kind.PROPERTY_READ
                        ? List.of(
                                new BusinessSecuritySuite.Assertion(
                                        "/privateNote", BusinessSecuritySuite.Match.ABSENT, null))
                        : List.<BusinessSecuritySuite.Assertion>of();
        return new BusinessSecuritySuite.Case(
                "read-policy",
                kind,
                "GET",
                "/orders/{id}",
                role,
                List.of(
                        step(
                                "owner-control",
                                BusinessSecuritySuite.Phase.CONTROL,
                                "admin",
                                "GET",
                                "/orders/{id}",
                                200,
                                List.of(),
                                Map.of()),
                        step(
                                "foreign-read",
                                BusinessSecuritySuite.Phase.PROBE,
                                role,
                                "GET",
                                "/orders/{id}",
                                kind == BusinessSecuritySuite.Kind.PROPERTY_READ ? 200 : 403,
                                assertions,
                                Map.of())),
                List.of());
    }

    BusinessSecuritySuite.Case writeCase(BusinessSecuritySuite.Kind kind) {
        String method = kind == BusinessSecuritySuite.Kind.PROPERTY_WRITE ? "PATCH" : "POST";
        String path =
                "/orders/{id}"
                        + (kind == BusinessSecuritySuite.Kind.TRANSITION
                                ? "/cancel"
                                : kind == BusinessSecuritySuite.Kind.REPLAY ? "/refund" : "");
        List<BusinessSecuritySuite.Step> steps = new ArrayList<>();
        if (kind == BusinessSecuritySuite.Kind.REPLAY)
            steps.add(
                    step(
                            "first-refund",
                            BusinessSecuritySuite.Phase.CONTROL,
                            "owner",
                            "POST",
                            path,
                            200,
                            List.of(),
                            Map.of()));
        steps.add(
                step(
                        "before",
                        BusinessSecuritySuite.Phase.CONTROL,
                        "owner",
                        "GET",
                        "/orders/{id}",
                        200,
                        List.of(),
                        Map.of(
                                "saved",
                                kind == BusinessSecuritySuite.Kind.PROPERTY_WRITE
                                        ? "/price"
                                        : kind == BusinessSecuritySuite.Kind.REPLAY
                                                ? "/refunds"
                                                : "/state")));
        steps.add(
                step(
                        "forbidden",
                        BusinessSecuritySuite.Phase.PROBE,
                        "owner",
                        method,
                        path,
                        kind == BusinessSecuritySuite.Kind.PROPERTY_WRITE ? 403 : 409,
                        List.of(),
                        Map.of()));
        steps.add(
                step(
                        "after",
                        BusinessSecuritySuite.Phase.VERIFY,
                        "owner",
                        "GET",
                        "/orders/{id}",
                        200,
                        List.of(
                                equals(
                                        kind == BusinessSecuritySuite.Kind.PROPERTY_WRITE
                                                ? "/price"
                                                : kind == BusinessSecuritySuite.Kind.REPLAY
                                                        ? "/refunds"
                                                        : "/state",
                                        "${saved}")),
                        Map.of()));
        return new BusinessSecuritySuite.Case(
                "write-policy",
                kind,
                method,
                path,
                "owner",
                steps,
                List.of(
                        step(
                                "cleanup",
                                BusinessSecuritySuite.Phase.CONTROL,
                                "owner",
                                "DELETE",
                                "/orders/{id}",
                                204,
                                List.of(),
                                Map.of())));
    }

    BusinessSecuritySuite suite(BusinessSecuritySuite.Case test) {
        List<BusinessSecuritySuite.Exclusion> exclusions = new ArrayList<>();
        for (var op :
                new io.testforge.api.explorer.OperationSelector().select("fixture", document()))
            if (!(op.method().equals(test.method()) && op.pathTemplate().equals(test.path())))
                exclusions.add(
                        new BusinessSecuritySuite.Exclusion(
                                test.kind(),
                                op.method(),
                                op.pathTemplate(),
                                test.role(),
                                "fixture-other-operation"));
        return new BusinessSecuritySuite(
                "orders",
                "v1",
                List.of(test.role()),
                Set.of(test.kind()),
                List.of(test),
                exclusions,
                Map.of(
                        "admin",
                        "tenant-a",
                        "owner",
                        "tenant-a",
                        "other",
                        "tenant-a",
                        "tenant",
                        "tenant-b"));
    }

    BusinessSecurityReport run(BusinessSecuritySuite.Case test) {
        return runner.run(
                document(),
                suite(test),
                Map.of("order", mapper.getNodeFactory().textNode("fixture")));
    }

    @Test
    void secureReadPoliciesMeasurePropertyObjectAndTenantAccessWithoutInheritingAdminDefaults() {
        for (var kind :
                List.of(
                        BusinessSecuritySuite.Kind.PROPERTY_READ,
                        BusinessSecuritySuite.Kind.OBJECT_ACCESS,
                        BusinessSecuritySuite.Kind.TENANT_ACCESS)) {
            String role =
                    kind == BusinessSecuritySuite.Kind.PROPERTY_READ
                            ? "owner"
                            : kind == BusinessSecuritySuite.Kind.OBJECT_ACCESS ? "other" : "tenant";
            assertThat(run(readCase(kind, role)).complete()).isTrue();
        }
        assertThat(collector.findings()).isEmpty();
    }

    @Test
    void brokenReadPoliciesProduceDistinctValueFreeFindings() throws Exception {
        broken = true;
        run(readCase(BusinessSecuritySuite.Kind.PROPERTY_READ, "owner"));
        run(readCase(BusinessSecuritySuite.Kind.OBJECT_ACCESS, "other"));
        run(readCase(BusinessSecuritySuite.Kind.TENANT_ACCESS, "tenant"));
        assertThat(collector.findings()).hasSize(3).allMatch(f -> f.severity() == Severity.HIGH);
        new SecurityRunner(
                        new SecurityProperties(null, dir.toString(), null, null, null, null),
                        collector,
                        ArtifactSink.NO_OP,
                        () -> true)
                .run();
        try (var files = Files.list(dir)) {
            for (Path file : files.toList())
                assertThat(Files.readString(file)).doesNotContain(privateValue, credential);
        }
    }

    @Test
    void secureWritesTransitionsAndReplayVerifyPersistedStateAndCleanup() {
        for (var kind :
                List.of(
                        BusinessSecuritySuite.Kind.PROPERTY_WRITE,
                        BusinessSecuritySuite.Kind.TRANSITION,
                        BusinessSecuritySuite.Kind.REPLAY))
            assertThat(run(writeCase(kind)).complete()).isTrue();
        assertThat(collector.findings()).isEmpty();
        assertThat(cleanup).hasValue(3);
    }

    @Test
    void denialStatusCannotHideWritesOrDuplicateBusinessEffects() {
        broken = true;
        for (var kind :
                List.of(
                        BusinessSecuritySuite.Kind.PROPERTY_WRITE,
                        BusinessSecuritySuite.Kind.TRANSITION,
                        BusinessSecuritySuite.Kind.REPLAY))
            assertThat(run(writeCase(kind)).complete()).isTrue();
        assertThat(collector.findings()).hasSize(3);
        assertThat(cleanup).hasValue(3);
    }

    @Test
    void missingCoverageIsExplicitAndCannotPassTheSharedGate() {
        var empty =
                new BusinessSecuritySuite(
                        "orders",
                        "v1",
                        List.of("owner"),
                        Set.of(BusinessSecuritySuite.Kind.OBJECT_ACCESS),
                        List.of(),
                        List.of());
        var report = runner.run(document(), empty, Map.of());
        assertThat(report.complete()).isFalse();
        assertThat(report.cells()).hasSize(5).allMatch(c -> c.status().equals("not_measured"));
        assertThat(requests).hasValue(0);
        assertThatThrownBy(
                        () ->
                                new SecurityRunner(
                                                new SecurityProperties(
                                                        null,
                                                        dir.toString(),
                                                        null,
                                                        null,
                                                        null,
                                                        null),
                                                collector,
                                                ArtifactSink.NO_OP,
                                                () -> true)
                                        .assertAcceptable())
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void failedPositiveControlStopsProbesButCleanupStillRuns() {
        controlFails = true;
        assertThat(run(writeCase(BusinessSecuritySuite.Kind.PROPERTY_WRITE)).complete()).isFalse();
        assertThat(cleanup).hasValue(1);
        assertThat(price).isEqualTo(50);
        assertThat(collector.findings()).isEmpty();
    }

    @Test
    void redirectAndOversizeResponsesAreIncompleteRatherThanProofOfProtection() {
        redirect = true;
        assertThat(run(readCase(BusinessSecuritySuite.Kind.OBJECT_ACCESS, "other")).complete())
                .isFalse();
        redirect = false;
        oversize = true;
        runner.close();
        runner = runner(properties(null, 128, null, null));
        assertThat(run(readCase(BusinessSecuritySuite.Kind.PROPERTY_READ, "owner")).complete())
                .isFalse();
        assertThat(collector.findings()).isEmpty();
    }

    @Test
    void writesAndImpossibleBudgetsAreRejectedBeforeAnyTraffic() {
        runner.close();
        runner = runner(properties(false, null, null, null));
        assertThatThrownBy(() -> run(writeCase(BusinessSecuritySuite.Kind.PROPERTY_WRITE)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(requests).hasValue(0);
        runner.close();
        runner = runner(properties(true, null, 1, null));
        assertThatThrownBy(() -> run(writeCase(BusinessSecuritySuite.Kind.PROPERTY_WRITE)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(requests).hasValue(0);
    }

    @Test
    void missingRuntimeBindingsCannotSendAnUnscopedRequest() {
        var test = readCase(BusinessSecuritySuite.Kind.OBJECT_ACCESS, "other");
        assertThat(runner.run(document(), suite(test), Map.of()).complete()).isFalse();
        assertThat(
                        runner.run(
                                        document(),
                                        suite(test),
                                        Map.of("order", mapper.getNodeFactory().textNode(".")))
                                .complete())
                .isFalse();
        assertThat(requests).hasValue(0);
    }

    @Test
    void conflictingExclusionsAndCasesAreRejectedBeforeTraffic() {
        var test = readCase(BusinessSecuritySuite.Kind.OBJECT_ACCESS, "other");
        var original = suite(test);
        var exclusions = new ArrayList<>(original.exclusions());
        exclusions.add(
                new BusinessSecuritySuite.Exclusion(
                        test.kind(), test.method(), test.path(), test.role(), "policy-ticket"));
        var invalid =
                new BusinessSecuritySuite(
                        original.id(),
                        original.version(),
                        original.roles(),
                        original.checks(),
                        original.cases(),
                        exclusions);
        assertThatThrownBy(() -> runner.run(document(), invalid, Map.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(requests).hasValue(0);
    }

    @Test
    void proposalsAreNoTrafficCandidatesRatherThanInferredPermissions() {
        var proposed =
                new BusinessSecurityPlanner()
                        .propose(
                                document(),
                                suite(readCase(BusinessSecuritySuite.Kind.OBJECT_ACCESS, "other")),
                                100);
        assertThat(proposed).hasSize(5).allMatch(c -> c.status().equals("needs_project_policy"));
        assertThat(proposed.toString()).doesNotContain(credential, privateValue);
        assertThat(requests).hasValue(0);
    }

    @Test
    void tenantChecksRequireDeclaredDistinctTenantsRatherThanRoleNameGuessing() {
        var test = readCase(BusinessSecuritySuite.Kind.TENANT_ACCESS, "tenant");
        var original = suite(test);
        var invalid =
                new BusinessSecuritySuite(
                        original.id(),
                        original.version(),
                        original.roles(),
                        original.checks(),
                        original.cases(),
                        original.exclusions());
        assertThatThrownBy(() -> runner.run(document(), invalid, Map.of()))
                .hasMessageContaining("distinct tenant aliases");
        assertThat(requests).hasValue(0);
    }

    @Test
    void replayWithoutAPrecedingIdenticalSuccessIsRejected() {
        var original = writeCase(BusinessSecuritySuite.Kind.REPLAY);
        var invalid =
                new BusinessSecuritySuite.Case(
                        original.id(),
                        original.kind(),
                        original.method(),
                        original.path(),
                        original.role(),
                        original.steps().subList(1, original.steps().size()),
                        original.cleanup());
        assertThatThrownBy(() -> run(invalid)).hasMessageContaining("replay needs");
        assertThat(requests).hasValue(0);
    }

    @Test
    void lastWriteMustHaveSubsequentStateVerification() {
        var original = writeCase(BusinessSecuritySuite.Kind.PROPERTY_WRITE);
        var steps = new ArrayList<>(original.steps());
        steps.add(
                step(
                        "late-write",
                        BusinessSecuritySuite.Phase.PROBE,
                        "owner",
                        "PATCH",
                        "/orders/{id}",
                        403,
                        List.of(),
                        Map.of()));
        var invalid =
                new BusinessSecuritySuite.Case(
                        original.id(),
                        original.kind(),
                        original.method(),
                        original.path(),
                        original.role(),
                        steps,
                        original.cleanup());
        assertThatThrownBy(() -> run(invalid)).hasMessageContaining("persisted-state");
        assertThat(requests).hasValue(0);
    }

    @Test
    void expiredSuiteBudgetCannotStartRequests() {
        runner.close();
        runner = runner(properties(true, null, null, Duration.ofNanos(1)));
        assertThat(run(readCase(BusinessSecuritySuite.Kind.OBJECT_ACCESS, "other")).complete())
                .isFalse();
        assertThat(requests).hasValue(0);
    }

    @Test
    void schemaHintsNeverConvertDocumentationFlagsIntoPermissions() {
        var document = document();
        var schema =
                new io.swagger.v3.oas.models.media.ObjectSchema()
                        .addProperty(
                                "password",
                                new io.swagger.v3.oas.models.media.StringSchema().writeOnly(true))
                        .addProperty(
                                "ownerId",
                                new io.swagger.v3.oas.models.media.StringSchema().readOnly(true));
        document.getPaths()
                .get("/orders/{id}")
                .getGet()
                .responses(
                        new io.swagger.v3.oas.models.responses.ApiResponses()
                                .addApiResponse(
                                        "200",
                                        new io.swagger.v3.oas.models.responses.ApiResponse()
                                                .content(
                                                        new io.swagger.v3.oas.models.media.Content()
                                                                .addMediaType(
                                                                        "application/json",
                                                                        new io.swagger.v3.oas.models
                                                                                        .media
                                                                                        .MediaType()
                                                                                .schema(schema)))));
        var candidates =
                new BusinessSecurityPlanner()
                        .propose(
                                document,
                                suite(readCase(BusinessSecuritySuite.Kind.OBJECT_ACCESS, "other")),
                                100);
        assertThat(candidates)
                .anyMatch(c -> c.schemaHints().contains("writeOnly response field: password"));
        assertThat(candidates).allMatch(c -> c.status().equals("needs_project_policy"));
        assertThat(requests).hasValue(0);
    }

    @Test
    void cleanupFailureLeavesCoverageIncompleteWithoutErasingObservedViolations() {
        broken = true;
        cleanupFails = true;
        assertThat(run(writeCase(BusinessSecuritySuite.Kind.PROPERTY_WRITE)).complete()).isFalse();
        assertThat(collector.findings()).hasSize(1);
        assertThat(collector.coverage()).anyMatch(c -> c.status().equals("not_measured"));
    }

    @Test
    void methodOverrideCannotBypassTheWriteSwitches() {
        var original = readCase(BusinessSecuritySuite.Kind.OBJECT_ACCESS, "other");
        var control = original.steps().get(0);
        var overridden =
                new PreparedRequest(
                        "GET",
                        "/orders/{id}",
                        Map.of("id", "${order}"),
                        Map.of("_method", "DELETE"));
        var invalid =
                new BusinessSecuritySuite.Case(
                        original.id(),
                        original.kind(),
                        original.method(),
                        original.path(),
                        original.role(),
                        List.of(
                                new BusinessSecuritySuite.Step(
                                        control.id(),
                                        control.phase(),
                                        control.role(),
                                        overridden,
                                        control.statuses(),
                                        control.assertions(),
                                        control.captures()),
                                original.steps().get(1)),
                        List.of());
        assertThatThrownBy(() -> run(invalid)).hasMessageContaining("method-override");
        assertThat(requests).hasValue(0);
    }

    @Test
    void jsonBindingsPreserveTypesAndCannotBreakOutOfTheirJsonValue() throws Exception {
        var original = writeCase(BusinessSecuritySuite.Kind.PROPERTY_WRITE);
        var steps = new ArrayList<>(original.steps());
        var probe = steps.get(1);
        steps.set(
                1,
                new BusinessSecuritySuite.Step(
                        probe.id(),
                        probe.phase(),
                        probe.role(),
                        new PreparedRequest(
                                "PATCH",
                                "/orders/{id}",
                                Map.of("id", "${order}"),
                                Map.of(),
                                "{\"price\":\"${amount}\",\"ownerId\":\"${foreign}\"}",
                                "application/json"),
                        probe.statuses(),
                        probe.assertions(),
                        probe.captures()));
        var test =
                new BusinessSecuritySuite.Case(
                        original.id(),
                        original.kind(),
                        original.method(),
                        original.path(),
                        original.role(),
                        steps,
                        original.cleanup());
        String supplied = "quote\" , \"admin\":true";
        assertThat(
                        runner.run(
                                        document(),
                                        suite(test),
                                        Map.of(
                                                "order",
                                                mapper.getNodeFactory().textNode("fixture"),
                                                "amount",
                                                mapper.getNodeFactory().numberNode(3),
                                                "foreign",
                                                mapper.getNodeFactory().textNode(supplied)))
                                .complete())
                .isTrue();
        JsonNode sent = mapper.readTree(patchBody.get());
        assertThat(sent.path("price").isNumber()).isTrue();
        assertThat(sent.path("ownerId").asText()).isEqualTo(supplied);
        assertThat(sent.has("admin")).isFalse();
    }

    @Test
    void enabledButUnexecutedBusinessBlockCannotHideBehindOtherMeasurements() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withConfiguration(
                        org.springframework.boot.autoconfigure.AutoConfigurations.of(
                                io.testforge.http.TestForgeHttpAutoConfiguration.class,
                                TestForgeSecurityAutoConfiguration.class))
                .withPropertyValues(
                        "forge.security.business.enabled=true", "forge.security.output-dir=" + dir)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(BusinessSecurityRunner.class);
                            var shared = context.getBean(SecurityCollector.class);
                            shared.covered("other-block", "run", null, "measured", null);
                            assertThatThrownBy(
                                            context.getBean(SecurityRunner.class)::assertAcceptable)
                                    .isInstanceOf(AssertionError.class);
                            assertThat(context.getBean(SecurityRunner.class).run().coverage())
                                    .anyMatch(c -> c.check().equals("block-business"));
                        });
    }

    @Test
    void replayProposalsIncludeTheFirstSuccessAndAStateCheckpoint() {
        var candidates =
                new BusinessSecurityPlanner()
                        .propose(
                                document(),
                                suite(writeCase(BusinessSecuritySuite.Kind.REPLAY)),
                                100);
        var refund =
                candidates.stream()
                        .filter(c -> c.operation().equals("POST /orders/{id}/refund"))
                        .findFirst()
                        .orElseThrow();
        assertThat(refund.suggestedSequence())
                .extracting(BusinessSecurityPlanner.ProposedStep::phase)
                .containsExactly("CONTROL", "CONTROL", "PROBE", "VERIFY");
        assertThatThrownBy(
                        () ->
                                new BusinessSecurityPlanner()
                                        .propose(
                                                document(),
                                                suite(writeCase(BusinessSecuritySuite.Kind.REPLAY)),
                                                1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).hasValue(0);
    }

    @Test
    void verificationCannotAccidentallyReadAnotherFixture() {
        var original = writeCase(BusinessSecuritySuite.Kind.PROPERTY_WRITE);
        var steps = new ArrayList<>(original.steps());
        var verify = steps.get(2);
        steps.set(
                2,
                new BusinessSecuritySuite.Step(
                        verify.id(),
                        verify.phase(),
                        verify.role(),
                        new PreparedRequest(
                                "GET", "/orders/{id}", Map.of("id", "another-order"), Map.of()),
                        verify.statuses(),
                        verify.assertions(),
                        verify.captures()));
        var invalid =
                new BusinessSecuritySuite.Case(
                        original.id(),
                        original.kind(),
                        original.method(),
                        original.path(),
                        original.role(),
                        steps,
                        original.cleanup());
        assertThatThrownBy(() -> run(invalid)).hasMessageContaining("persisted-state");
        assertThat(requests).hasValue(0);
    }

    @Test
    void ownerControlMustReadTheSameFixtureAsTheForeignProbe() {
        var original = readCase(BusinessSecuritySuite.Kind.OBJECT_ACCESS, "other");
        var control = original.steps().get(0);
        var invalid =
                new BusinessSecuritySuite.Case(
                        original.id(),
                        original.kind(),
                        original.method(),
                        original.path(),
                        original.role(),
                        List.of(
                                new BusinessSecuritySuite.Step(
                                        control.id(),
                                        control.phase(),
                                        control.role(),
                                        new PreparedRequest(
                                                "GET",
                                                "/orders/{id}",
                                                Map.of("id", "unrelated-order"),
                                                Map.of()),
                                        control.statuses(),
                                        control.assertions(),
                                        control.captures()),
                                original.steps().get(1)),
                        List.of());
        assertThatThrownBy(() -> run(invalid)).hasMessageContaining("distinct owner role");
        assertThat(requests).hasValue(0);
    }

    @Test
    void aStalledResponseBodyCannotOutliveTheRequestBudget() {
        stallBody = true;
        runner.close();
        runner = runner(properties(true, null, null, Duration.ofMillis(150)));
        assertTimeoutPreemptively(
                Duration.ofSeconds(3),
                () ->
                        assertThat(
                                        run(readCase(
                                                        BusinessSecuritySuite.Kind.OBJECT_ACCESS,
                                                        "other"))
                                                .complete())
                                .isFalse());
        assertThat(collector.findings()).isEmpty();
    }

    @Test
    void inputBindingBudgetIsEnforcedBeforeAnyTraffic() {
        runner.close();
        runner = runner(properties(true, 128, null, null));
        var test = readCase(BusinessSecuritySuite.Kind.OBJECT_ACCESS, "other");
        assertThatThrownBy(
                        () ->
                                runner.run(
                                        document(),
                                        suite(test),
                                        Map.of(
                                                "left",
                                                mapper.getNodeFactory().textNode("a".repeat(80)),
                                                "right",
                                                mapper.getNodeFactory().textNode("b".repeat(80)))))
                .hasMessageContaining("binding bound");
        assertThat(requests).hasValue(0);
    }
}
