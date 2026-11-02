package io.testforge.example;

import static io.testforge.security.BusinessInvariant.Value.binding;
import static io.testforge.security.BusinessInvariant.Value.item;
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
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.PathParameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.testforge.api.explorer.OperationSelector;
import io.testforge.api.explorer.PreparedRequest;
import io.testforge.auth.TestForgeAuthAutoConfiguration;
import io.testforge.http.TestForgeHttpAutoConfiguration;
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

class BusinessEffectsExampleTest {
    enum Mode {
        SECURE,
        DOUBLE_CHARGE,
        NO_RESERVE,
        WRONG_TOTAL,
        NEGATIVE_STOCK,
        OVER_REFUND
    }

    final ObjectMapper mapper = new ObjectMapper();

    BusinessInvariant.Value n(String number) {
        return literal(DecimalNode.valueOf(new BigDecimal(number)));
    }

    BusinessInvariant rule(
            String id,
            BusinessInvariant.Compare compare,
            BusinessInvariant.Value actual,
            BusinessInvariant.Value expected) {
        return new BusinessInvariant(id, compare, actual, expected);
    }

    BusinessSecuritySuite.Step step(
            String id,
            BusinessSecuritySuite.Phase phase,
            String method,
            String path,
            Map<String, String> captures,
            List<BusinessInvariant> rules) {
        return new BusinessSecuritySuite.Step(
                id,
                phase,
                "owner",
                new PreparedRequest(
                        method,
                        path,
                        path.contains("{id}") ? Map.of("id", "${order}") : Map.of(),
                        Map.of(),
                        method.equals("POST") ? "{}" : null,
                        method.equals("POST") ? "application/json" : null),
                Set.of(method.equals("DELETE") ? 204 : 200),
                List.of(),
                captures,
                rules,
                null);
    }

    Schema<?> object(Map<String, Schema> fields) {
        var schema = new ObjectSchema();
        fields.forEach(schema::addProperty);
        schema.setAdditionalProperties(false);
        return schema;
    }

    Operation op(Schema<?> schema) {
        return new Operation()
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
                                                                                .schema(schema)))));
    }

    OpenAPI document() {
        var order =
                object(
                        Map.of(
                                "id",
                                new StringSchema(),
                                "total",
                                new NumberSchema(),
                                "qty",
                                new IntegerSchema(),
                                "state",
                                new StringSchema(),
                                "refunded",
                                new NumberSchema(),
                                "items",
                                new ArraySchema()
                                        .items(
                                                object(
                                                        Map.of(
                                                                "qty",
                                                                new IntegerSchema(),
                                                                "unitPrice",
                                                                new NumberSchema())))));
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
                                                                .required(true))
                                                .get(op(order))
                                                .delete(
                                                        new Operation()
                                                                .responses(
                                                                        new ApiResponses()
                                                                                .addApiResponse(
                                                                                        "204",
                                                                                        new ApiResponse()))))
                                .addPathItem(
                                        "/orders/{id}/pay",
                                        new PathItem()
                                                .addParametersItem(
                                                        new PathParameter()
                                                                .name("id")
                                                                .required(true))
                                                .post(
                                                        op(
                                                                object(
                                                                        Map.of(
                                                                                "state",
                                                                                new StringSchema())))))
                                .addPathItem(
                                        "/orders/{id}/refund",
                                        new PathItem()
                                                .addParametersItem(
                                                        new PathParameter()
                                                                .name("id")
                                                                .required(true))
                                                .post(
                                                        op(
                                                                object(
                                                                        Map.of(
                                                                                "state",
                                                                                new StringSchema())))))
                                .addPathItem(
                                        "/account",
                                        new PathItem()
                                                .get(
                                                        op(
                                                                object(
                                                                        Map.of(
                                                                                "balance",
                                                                                new NumberSchema())))))
                                .addPathItem(
                                        "/stock",
                                        new PathItem()
                                                .get(
                                                        op(
                                                                object(
                                                                        Map.of(
                                                                                "available",
                                                                                new IntegerSchema()))))));
    }

    BusinessSecuritySuite suite(boolean refund, OpenAPI doc) {
        String action = refund ? "/orders/{id}/refund" : "/orders/{id}/pay";
        List<BusinessSecuritySuite.Step> steps = new ArrayList<>();
        steps.add(
                step(
                        "order-before",
                        BusinessSecuritySuite.Phase.CONTROL,
                        "GET",
                        "/orders/{id}",
                        Map.of("amount", "/total", "qty", "/qty", "before-refunded", "/refunded"),
                        List.of()));
        steps.add(
                step(
                        "account-before",
                        BusinessSecuritySuite.Phase.CONTROL,
                        "GET",
                        "/account",
                        Map.of("before-balance", "/balance"),
                        List.of()));
        if (!refund)
            steps.add(
                    step(
                            "stock-before",
                            BusinessSecuritySuite.Phase.CONTROL,
                            "GET",
                            "/stock",
                            Map.of("before-stock", "/available"),
                            List.of()));
        steps.add(
                step(
                        "action",
                        BusinessSecuritySuite.Phase.PROBE,
                        "POST",
                        action,
                        Map.of(),
                        List.of()));
        steps.add(
                step(
                        "account-after",
                        BusinessSecuritySuite.Phase.VERIFY,
                        "GET",
                        "/account",
                        Map.of(),
                        List.of(
                                rule(
                                        "account-delta",
                                        BusinessInvariant.Compare.NUMERIC_EQUALS,
                                        response("/balance"),
                                        operation(
                                                refund
                                                        ? BusinessInvariant.Source.ADD
                                                        : BusinessInvariant.Source.SUBTRACT,
                                                binding("before-balance"),
                                                refund ? n("0.20") : binding("amount"))))));
        if (!refund)
            steps.add(
                    step(
                            "stock-after",
                            BusinessSecuritySuite.Phase.VERIFY,
                            "GET",
                            "/stock",
                            Map.of(),
                            List.of(
                                    rule(
                                            "stock-delta",
                                            BusinessInvariant.Compare.NUMERIC_EQUALS,
                                            response("/available"),
                                            operation(
                                                    BusinessInvariant.Source.SUBTRACT,
                                                    binding("before-stock"),
                                                    binding("qty"))),
                                    rule(
                                            "stock-nonnegative",
                                            BusinessInvariant.Compare.AT_LEAST,
                                            response("/available"),
                                            n("0")))));
        var rules =
                refund
                        ? List.of(
                                rule(
                                        "refund-delta",
                                        BusinessInvariant.Compare.NUMERIC_EQUALS,
                                        response("/refunded"),
                                        operation(
                                                BusinessInvariant.Source.ADD,
                                                binding("before-refunded"),
                                                n("0.20"))),
                                rule(
                                        "refund-cap",
                                        BusinessInvariant.Compare.AT_MOST,
                                        response("/refunded"),
                                        response("/total")))
                        : List.of(
                                rule(
                                        "line-total",
                                        BusinessInvariant.Compare.NUMERIC_EQUALS,
                                        response("/total"),
                                        operation(
                                                BusinessInvariant.Source.SUM,
                                                response("/items"),
                                                operation(
                                                        BusinessInvariant.Source.MULTIPLY,
                                                        item("/qty"),
                                                        item("/unitPrice")))),
                                rule(
                                        "paid-state",
                                        BusinessInvariant.Compare.JSON_EQUALS,
                                        response("/state"),
                                        literal(TextNode.valueOf("paid"))));
        steps.add(
                step(
                        "order-after",
                        BusinessSecuritySuite.Phase.VERIFY,
                        "GET",
                        "/orders/{id}",
                        Map.of(),
                        rules));
        var test =
                new BusinessSecuritySuite.Case(
                        refund ? "refund" : "payment",
                        BusinessSecuritySuite.Kind.BUSINESS_INVARIANT,
                        "POST",
                        action,
                        "owner",
                        steps,
                        List.of(
                                step(
                                        "cleanup",
                                        BusinessSecuritySuite.Phase.CONTROL,
                                        "DELETE",
                                        "/orders/{id}",
                                        Map.of(),
                                        List.of())));
        var excluded = new ArrayList<BusinessSecuritySuite.Exclusion>();
        for (var operation : new OperationSelector().select("shop", doc))
            if (!operation.key().equals("POST " + action))
                excluded.add(
                        new BusinessSecuritySuite.Exclusion(
                                test.kind(),
                                operation.method(),
                                operation.pathTemplate(),
                                "owner",
                                "fixture-controls"));
        return new BusinessSecuritySuite(
                refund ? "refund-policy" : "payment-policy",
                "v1",
                List.of("owner"),
                Set.of(test.kind()),
                List.of(test),
                excluded);
    }

    @Test
    void exactOrderPaymentAndRefundReconcileRelatedResources(@TempDir Path dir) throws Exception {
        exercise(dir.resolve("pay"), Mode.SECURE, false);
        exercise(dir.resolve("refund"), Mode.SECURE, true);
    }

    @Test
    void businessDefectsAreDetectedAcrossOrderBalanceAndStock(@TempDir Path dir) throws Exception {
        for (Mode mode :
                List.of(Mode.DOUBLE_CHARGE, Mode.NO_RESERVE, Mode.WRONG_TOTAL, Mode.NEGATIVE_STOCK))
            exercise(dir.resolve(mode.name()), mode, false);
        exercise(dir.resolve("over-refund"), Mode.OVER_REFUND, true);
    }

    private void exercise(Path dir, Mode mode, boolean refund) throws Exception {
        String id = UUID.randomUUID().toString(), token = "synthetic-" + UUID.randomUUID();
        final BigDecimal[] balance = {new BigDecimal(refund ? "9.60" : "10.00")},
                refunded = {BigDecimal.ZERO};
        final int[] stock = {10}, deletes = {0};
        final String[] state = {refund ? "paid" : "created"};
        BigDecimal total = new BigDecimal(mode == Mode.WRONG_TOTAL ? "0.41" : "0.40");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                e -> {
                    String path = e.getRequestURI().getPath();
                    String method = e.getRequestMethod();
                    int status = 200;
                    String body = "{}";
                    if (!("Bearer " + token)
                            .equals(e.getRequestHeaders().getFirst("Authorization"))) status = 403;
                    else if (method.equals("DELETE")) {
                        status = 204;
                        deletes[0]++;
                    } else if (method.equals("POST") && path.endsWith("/pay")) {
                        balance[0] =
                                balance[0].subtract(
                                        mode == Mode.DOUBLE_CHARGE
                                                ? total.multiply(new BigDecimal("2"))
                                                : total);
                        stock[0] =
                                mode == Mode.NO_RESERVE
                                        ? stock[0]
                                        : mode == Mode.NEGATIVE_STOCK ? -1 : stock[0] - 3;
                        state[0] = "paid";
                    } else if (method.equals("POST") && path.endsWith("/refund")) {
                        var amount = new BigDecimal(mode == Mode.OVER_REFUND ? "0.80" : "0.20");
                        refunded[0] = refunded[0].add(amount);
                        balance[0] = balance[0].add(amount);
                    } else if (path.equals("/account"))
                        body = "{\"balance\":" + balance[0].toPlainString() + "}";
                    else if (path.equals("/stock")) body = "{\"available\":" + stock[0] + "}";
                    else
                        body =
                                "{\"id\":\""
                                        + id
                                        + "\",\"total\":"
                                        + total.toPlainString()
                                        + ",\"qty\":3,\"state\":\""
                                        + state[0]
                                        + "\",\"refunded\":"
                                        + refunded[0].toPlainString()
                                        + ",\"items\":[{\"qty\":2,\"unitPrice\":0.10},{\"qty\":1,\"unitPrice\":0.20}]}";
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
                                var doc = document();
                                var policy = suite(refund, doc);
                                c.getBean(BusinessPolicyRunner.class)
                                        .writeReview(doc, policy, Set.of("order"));
                                assertThat(
                                                c.getBean(BusinessSecurityRunner.class)
                                                        .run(
                                                                doc,
                                                                policy,
                                                                Map.of(
                                                                        "order",
                                                                        TextNode.valueOf(id)))
                                                        .complete())
                                        .isTrue();
                                var gate = c.getBean(SecurityRunner.class);
                                if (mode == Mode.SECURE)
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
                                                .doesNotContain(id, token);
                                }
                            });
        } finally {
            server.stop(0);
        }
    }
}
