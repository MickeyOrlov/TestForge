package io.testforge.example;

import static io.testforge.security.BusinessInvariant.Value.binding;
import static io.testforge.security.BusinessInvariant.Value.literal;
import static io.testforge.security.BusinessInvariant.Value.operation;
import static io.testforge.security.BusinessInvariant.Value.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.sun.net.httpserver.HttpServer;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.PathParameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.testforge.api.explorer.PreparedRequest;
import io.testforge.auth.TestForgeAuthAutoConfiguration;
import io.testforge.http.TestForgeHttpAutoConfiguration;
import io.testforge.security.BusinessIdempotency;
import io.testforge.security.BusinessInvariant;
import io.testforge.security.BusinessPolicyRunner;
import io.testforge.security.BusinessSecurityRunner;
import io.testforge.security.BusinessSecuritySuite;
import io.testforge.security.SecurityRunner;
import io.testforge.security.TestForgeSecurityAutoConfiguration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

class BusinessIdempotencyExampleTest {
    enum Scenario {
        REPEAT,
        CONFLICT,
        DENIED
    }

    enum Defect {
        NONE,
        REAPPLY,
        DIFFERENT_RECEIPT,
        CONFLICT_MUTATES,
        CONFLICT_ACCEPTED,
        DENIAL_MUTATES
    }

    final ObjectMapper mapper = new ObjectMapper();

    BusinessInvariant rule(String id, String pointer, BusinessInvariant.Value expected) {
        return new BusinessInvariant(
                id, BusinessInvariant.Compare.NUMERIC_EQUALS, response(pointer), expected);
    }

    BusinessInvariant.Value number(String n) {
        return literal(DecimalNode.valueOf(new BigDecimal(n)));
    }

    String action(boolean refund) {
        return "/orders/{id}/" + (refund ? "refund" : "pay");
    }

    BusinessSecuritySuite.Step read(
            String id,
            BusinessSecuritySuite.Phase phase,
            Map<String, String> captures,
            List<BusinessInvariant> rules) {
        return new BusinessSecuritySuite.Step(
                id,
                phase,
                "owner",
                new PreparedRequest("GET", "/orders/{id}", Map.of("id", "${order}"), Map.of()),
                Set.of(200),
                List.of(),
                captures,
                rules,
                null);
    }

    BusinessSecuritySuite.Step write(
            String id,
            BusinessSecuritySuite.Phase phase,
            boolean refund,
            String amount,
            int status,
            boolean receipt) {
        return new BusinessSecuritySuite.Step(
                id,
                phase,
                "owner",
                new PreparedRequest(
                        "POST",
                        action(refund),
                        Map.of("id", "${order}"),
                        Map.of(),
                        "{\"amount\":" + amount + "}",
                        "application/json"),
                Set.of(status),
                receipt
                        ? List.of(
                                new BusinessSecuritySuite.Assertion(
                                        "/receipt",
                                        BusinessSecuritySuite.Match.EQUALS,
                                        TextNode.valueOf("${receipt}")))
                        : List.of(),
                phase == BusinessSecuritySuite.Phase.CONTROL
                        ? Map.of("receipt", "/receipt")
                        : Map.of(),
                List.of(),
                null,
                new BusinessIdempotency("Idempotency-Key", "key"));
    }

    BusinessSecuritySuite suite(Scenario scenario, boolean refund) {
        var steps = new ArrayList<BusinessSecuritySuite.Step>();
        steps.add(
                read(
                        "before",
                        BusinessSecuritySuite.Phase.CONTROL,
                        Map.of("before", "/balance", "count-before", "/applied"),
                        List.of()));
        if (scenario != Scenario.DENIED) {
            steps.add(
                    write(
                            "first",
                            BusinessSecuritySuite.Phase.CONTROL,
                            refund,
                            "2.00",
                            200,
                            false));
            steps.add(
                    read(
                            "after-first",
                            BusinessSecuritySuite.Phase.CONTROL,
                            Map.of("after", "/balance", "count-after", "/applied"),
                            List.of(
                                    rule(
                                            "first-balance",
                                            "/balance",
                                            operation(
                                                    refund
                                                            ? BusinessInvariant.Source.ADD
                                                            : BusinessInvariant.Source.SUBTRACT,
                                                    binding("before"),
                                                    number("2"))),
                                    rule(
                                            "first-applied",
                                            "/applied",
                                            operation(
                                                    BusinessInvariant.Source.ADD,
                                                    binding("count-before"),
                                                    number("1"))))));
        }
        steps.add(
                write(
                        "repeat",
                        BusinessSecuritySuite.Phase.PROBE,
                        refund,
                        scenario == Scenario.CONFLICT
                                ? "3.00"
                                : scenario == Scenario.DENIED ? "-1.00" : "2.00",
                        scenario == Scenario.CONFLICT
                                ? 409
                                : scenario == Scenario.DENIED ? 422 : 200,
                        scenario == Scenario.REPEAT));
        steps.add(
                read(
                        "after-probe",
                        BusinessSecuritySuite.Phase.VERIFY,
                        Map.of(),
                        List.of(
                                rule(
                                        "balance-unchanged",
                                        "/balance",
                                        binding(scenario == Scenario.DENIED ? "before" : "after")),
                                rule(
                                        "applied-unchanged",
                                        "/applied",
                                        binding(
                                                scenario == Scenario.DENIED
                                                        ? "count-before"
                                                        : "count-after")))));
        var kind =
                scenario == Scenario.REPEAT
                        ? BusinessSecuritySuite.Kind.REPLAY
                        : BusinessSecuritySuite.Kind.BUSINESS_INVARIANT;
        var cleanup =
                new BusinessSecuritySuite.Step(
                        "cleanup",
                        BusinessSecuritySuite.Phase.CONTROL,
                        "owner",
                        new PreparedRequest(
                                "DELETE", "/orders/{id}", Map.of("id", "${order}"), Map.of()),
                        Set.of(204),
                        List.of(),
                        Map.of());
        var test =
                new BusinessSecuritySuite.Case(
                        "effects", kind, "POST", action(refund), "owner", steps, List.of(cleanup));
        return new BusinessSecuritySuite(
                "idempotency",
                "v1",
                List.of("owner"),
                Set.of(kind),
                List.of(test),
                List.of(
                        new BusinessSecuritySuite.Exclusion(
                                kind, "GET", "/orders/{id}", "owner", "fixture-read"),
                        new BusinessSecuritySuite.Exclusion(
                                kind, "DELETE", "/orders/{id}", "owner", "fixture-cleanup")));
    }

    OpenAPI document(boolean refund) {
        var state =
                new ObjectSchema()
                        .addProperty("balance", new NumberSchema())
                        .addProperty("applied", new NumberSchema());
        state.setAdditionalProperties(false);
        var receipt = new ObjectSchema().addProperty("receipt", new StringSchema());
        receipt.setAdditionalProperties(false);
        var input = new ObjectSchema().addProperty("amount", new NumberSchema());
        input.setAdditionalProperties(false);
        var write =
                new Operation()
                        .addParametersItem(
                                new HeaderParameter()
                                        .name("Idempotency-Key")
                                        .required(true)
                                        .schema(new StringSchema()))
                        .requestBody(
                                new RequestBody()
                                        .required(true)
                                        .content(
                                                new Content()
                                                        .addMediaType(
                                                                "application/json",
                                                                new MediaType().schema(input))))
                        .responses(
                                new ApiResponses()
                                        .addApiResponse("200", apiResponse(receipt))
                                        .addApiResponse("409", apiResponse(new ObjectSchema()))
                                        .addApiResponse("422", apiResponse(new ObjectSchema())));
        return new OpenAPI()
                .openapi("3.0.3")
                .paths(
                        new Paths()
                                .addPathItem(
                                        "/orders/{id}",
                                        new PathItem()
                                                .addParametersItem(
                                                        new PathParameter()
                                                                .name("id")
                                                                .required(true)
                                                                .schema(new StringSchema()))
                                                .get(
                                                        new Operation()
                                                                .responses(
                                                                        new ApiResponses()
                                                                                .addApiResponse(
                                                                                        "200",
                                                                                        apiResponse(
                                                                                                state))))
                                                .delete(
                                                        new Operation()
                                                                .responses(
                                                                        new ApiResponses()
                                                                                .addApiResponse(
                                                                                        "204",
                                                                                        new ApiResponse()))))
                                .addPathItem(
                                        action(refund),
                                        new PathItem()
                                                .addParametersItem(
                                                        new PathParameter()
                                                                .name("id")
                                                                .required(true)
                                                                .schema(new StringSchema()))
                                                .post(write)));
    }

    ApiResponse apiResponse(Schema<?> schema) {
        return new ApiResponse()
                .content(
                        new Content()
                                .addMediaType("application/json", new MediaType().schema(schema)));
    }

    @Test
    void paymentAndRefundRepeatsAndRejectedRequestsDoNotApplyExtraEffects(@TempDir Path dir)
            throws Exception {
        for (boolean refund : List.of(false, true))
            for (Scenario scenario : Scenario.values())
                exercise(dir.resolve(refund + "-" + scenario), scenario, Defect.NONE, refund);
    }

    @Test
    void duplicateEffectsChangedReceiptAndMutationDespiteRejectionAreDetected(@TempDir Path dir)
            throws Exception {
        exercise(dir.resolve("double-charge"), Scenario.REPEAT, Defect.REAPPLY, false);
        exercise(dir.resolve("double-refund"), Scenario.REPEAT, Defect.REAPPLY, true);
        exercise(dir.resolve("receipt"), Scenario.REPEAT, Defect.DIFFERENT_RECEIPT, false);
        exercise(
                dir.resolve("conflict-mutates"), Scenario.CONFLICT, Defect.CONFLICT_MUTATES, false);
        exercise(
                dir.resolve("conflict-accepted"),
                Scenario.CONFLICT,
                Defect.CONFLICT_ACCEPTED,
                false);
        exercise(dir.resolve("denial-mutates"), Scenario.DENIED, Defect.DENIAL_MUTATES, false);
    }

    @Test
    void invalidRuntimeKeyProducesIncompleteCoverageWithoutSendingItsRequest(@TempDir Path dir)
            throws Exception {
        exercise(dir, Scenario.DENIED, Defect.NONE, false, "invalid\r\nkey");
    }

    void exercise(Path dir, Scenario scenario, Defect defect, boolean refund) throws Exception {
        exercise(dir, scenario, defect, refund, UUID.randomUUID().toString());
    }

    void exercise(Path dir, Scenario scenario, Defect defect, boolean refund, String key)
            throws Exception {
        String id = UUID.randomUUID().toString(),
                token = "synthetic-" + UUID.randomUUID(),
                receipt = UUID.randomUUID().toString();
        BigDecimal[] balance = {new BigDecimal("10")}, stored = {null};
        int[] applied = {0}, deletes = {0}, posts = {0};
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                e -> {
                    int status = 200;
                    String body = "{}";
                    if (!("Bearer " + token)
                            .equals(e.getRequestHeaders().getFirst("Authorization"))) status = 403;
                    else if (e.getRequestMethod().equals("DELETE")) {
                        status = 204;
                        deletes[0]++;
                    } else if (e.getRequestMethod().equals("POST")) {
                        posts[0]++;
                        assertThat(e.getRequestHeaders().getFirst("Idempotency-Key"))
                                .isEqualTo(key);
                        BigDecimal amount =
                                mapper.readTree(e.getRequestBody().readAllBytes())
                                        .get("amount")
                                        .decimalValue();
                        boolean apply = false;
                        if (amount.signum() < 0) {
                            status = 422;
                            apply = defect == Defect.DENIAL_MUTATES;
                        } else if (stored[0] == null) {
                            stored[0] = amount;
                            apply = true;
                        } else if (stored[0].compareTo(amount) != 0) {
                            status = defect == Defect.CONFLICT_ACCEPTED ? 200 : 409;
                            apply =
                                    defect == Defect.CONFLICT_MUTATES
                                            || defect == Defect.CONFLICT_ACCEPTED;
                        } else apply = defect == Defect.REAPPLY;
                        if (apply) {
                            balance[0] =
                                    refund ? balance[0].add(amount) : balance[0].subtract(amount);
                            applied[0]++;
                        }
                        if (status == 200)
                            body =
                                    "{\"receipt\":\""
                                            + (posts[0] > 1 && defect == Defect.DIFFERENT_RECEIPT
                                                    ? UUID.randomUUID()
                                                    : receipt)
                                            + "\"}";
                    } else
                        body =
                                "{\"balance\":"
                                        + balance[0].toPlainString()
                                        + ",\"applied\":"
                                        + applied[0]
                                        + "}";
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
                                    TestForgeHttpAutoConfiguration.class,
                                    TestForgeAuthAutoConfiguration.class,
                                    TestForgeSecurityAutoConfiguration.class))
                    .withPropertyValues(
                            "forge.http.base-url=http://127.0.0.1:" + server.getAddress().getPort(),
                            "forge.auth.enabled=true",
                            "forge.auth.roles.owner.type=static",
                            "forge.auth.roles.owner.token=" + token,
                            "forge.security.business.enabled=true",
                            "forge.security.business.test-environment=true",
                            "forge.security.business.allowed-hosts[0]=127.0.0.1",
                            "forge.security.business.write-checks=true",
                            "forge.security.business.allow-unsafe-methods=true",
                            "forge.security.business.policy.required=true",
                            "forge.security.business.policy.review-dir=" + dir.resolve("reviews"),
                            "forge.security.output-dir=" + dir.resolve("reports"))
                    .run(
                            c -> {
                                assertThat(c).hasNotFailed();
                                var doc = document(refund);
                                var suite = suite(scenario, refund);
                                c.getBean(BusinessPolicyRunner.class)
                                        .writeReview(doc, suite, Set.of("order", "key"));
                                var report =
                                        c.getBean(BusinessSecurityRunner.class)
                                                .run(
                                                        doc,
                                                        suite,
                                                        Map.of(
                                                                "order",
                                                                TextNode.valueOf(id),
                                                                "key",
                                                                TextNode.valueOf(key)));
                                boolean valid = !key.contains("\r");
                                assertThat(report.complete()).isEqualTo(valid);
                                var gate = c.getBean(SecurityRunner.class);
                                if (!valid) {
                                    assertThat(posts[0]).isZero();
                                    assertThat(gate.run().healthy()).isFalse();
                                } else if (defect == Defect.NONE)
                                    assertThat(gate.assertAcceptable().healthy()).isTrue();
                                else {
                                    assertThat(gate.run().findings()).isNotEmpty();
                                    assertThatThrownBy(gate::assertAcceptable)
                                            .isInstanceOf(AssertionError.class);
                                }
                                assertThat(deletes[0]).isEqualTo(1);
                                try (var files = Files.walk(dir)) {
                                    for (Path file : files.filter(Files::isRegularFile).toList())
                                        assertThat(Files.readString(file))
                                                .doesNotContain(id, token, receipt, key);
                                } catch (java.io.IOException ex) {
                                    throw new AssertionError(ex);
                                }
                            });
        } finally {
            server.stop(0);
        }
    }
}
