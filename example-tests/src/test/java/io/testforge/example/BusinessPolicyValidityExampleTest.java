package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.PathParameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.testforge.api.explorer.PreparedRequest;
import io.testforge.auth.TestForgeAuthAutoConfiguration;
import io.testforge.core.TestForgeCoreAutoConfiguration;
import io.testforge.http.TestForgeHttpAutoConfiguration;
import io.testforge.security.BusinessPolicyReport;
import io.testforge.security.BusinessPolicyRunner;
import io.testforge.security.BusinessSecurityException;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class BusinessPolicyValidityExampleTest {
    OpenAPI document(String id, boolean amountString) {
        var schema = new ObjectSchema();
        schema.setAdditionalProperties(false);
        schema.addProperty(id, new StringSchema())
                .addProperty(
                        "amount",
                        amountString
                                ? new StringSchema()
                                : new io.swagger.v3.oas.models.media.IntegerSchema());
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
                                                                                                                                        schema))))
                                                                                .addApiResponse(
                                                                                        "403",
                                                                                        new ApiResponse()
                                                                                                .description(
                                                                                                        "denied"))))));
    }

    BusinessSecuritySuite suite(String pointer) {
        var request =
                new PreparedRequest("GET", "/orders/{id}", Map.of("id", "${order}"), Map.of());
        var control =
                new BusinessSecuritySuite.Step(
                        "owner-control",
                        BusinessSecuritySuite.Phase.CONTROL,
                        "owner",
                        request,
                        Set.of(200),
                        List.of(),
                        Map.of("saved-id", pointer));
        var probe =
                new BusinessSecuritySuite.Step(
                        "foreign-read",
                        BusinessSecuritySuite.Phase.PROBE,
                        "other",
                        request,
                        Set.of(403),
                        List.of(),
                        Map.of());
        return new BusinessSecuritySuite(
                "current-orders",
                "v1",
                List.of("other"),
                Set.of(BusinessSecuritySuite.Kind.OBJECT_ACCESS),
                List.of(
                        new BusinessSecuritySuite.Case(
                                "foreign-object",
                                BusinessSecuritySuite.Kind.OBJECT_ACCESS,
                                "GET",
                                "/orders/{id}",
                                "other",
                                List.of(control, probe),
                                List.of())),
                List.of());
    }

    ApplicationContextRunner context(Path dir, int port, String credential) {
        return new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                TestForgeCoreAutoConfiguration.class,
                                TestForgeHttpAutoConfiguration.class,
                                TestForgeAuthAutoConfiguration.class,
                                TestForgeSecurityAutoConfiguration.class))
                .withPropertyValues(
                        "forge.http.base-url=http://127.0.0.1:" + port,
                        "forge.security.output-dir=" + dir.resolve("reports"),
                        "forge.security.business.enabled=true",
                        "forge.security.business.test-environment=true",
                        "forge.security.business.allowed-hosts[0]=127.0.0.1",
                        "forge.security.business.policy.required=true",
                        "forge.security.business.policy.review-dir=" + dir.resolve("reviews"),
                        "forge.auth.enabled=true",
                        "forge.auth.roles.owner.type=static",
                        "forge.auth.roles.owner.token=" + credential,
                        "forge.auth.roles.other.type=static",
                        "forge.auth.roles.other.token=synthetic-other");
    }

    @Test
    void aRenamedCaptureStopsBeforeHttpAndExplicitReviewRestoresTheUpdatedPolicy(@TempDir Path dir)
            throws Exception {
        exercise(dir, true);
    }

    @Test
    void typeDriftCannotStayGreenJustBecauseTheExistingAssertionsDoNotUseThatField(
            @TempDir Path dir) throws Exception {
        exercise(dir, false);
    }

    private void exercise(Path dir, boolean rename) throws Exception {
        var count = new AtomicInteger();
        var changed = new AtomicBoolean();
        String credential = "synthetic-owner-" + UUID.randomUUID();
        String orderId = UUID.randomUUID().toString();
        var mapper = new ObjectMapper();
        var inputs = Map.of("order", mapper.getNodeFactory().textNode(orderId));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/orders",
                e -> {
                    count.incrementAndGet();
                    boolean owner =
                            ("Bearer " + credential)
                                    .equals(e.getRequestHeaders().getFirst("Authorization"));
                    String body =
                            owner
                                    ? "{\""
                                            + (changed.get() && rename ? "orderId" : "id")
                                            + "\":\""
                                            + orderId
                                            + "\",\"amount\":"
                                            + (changed.get() && !rename ? "\"50\"" : "50")
                                            + "}"
                                    : "{}";
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    e.getResponseHeaders().add("Content-Type", "application/json");
                    e.sendResponseHeaders(owner ? 200 : 403, bytes.length);
                    e.getResponseBody().write(bytes);
                    e.close();
                });
        server.start();
        try {
            var original = document("id", false);
            var suite = suite("/id");
            context(dir, server.getAddress().getPort(), credential)
                    .run(
                            c -> {
                                assertThat(c).hasNotFailed();
                                var policies = c.getBean(BusinessPolicyRunner.class);
                                assertThat(
                                                policies.assess(original, suite, inputs.keySet())
                                                        .status())
                                        .isEqualTo(BusinessPolicyReport.Status.REVIEW_REQUIRED);
                                assertThat(count).hasValue(0);
                                policies.writeReview(original, suite, inputs.keySet());
                                byte[] reviewed = Files.readAllBytes(policies.reviewFile(suite));
                                assertThat(c.getBean(SecurityRunner.class).run().measured())
                                        .isFalse();
                                assertThat(
                                                c.getBean(BusinessSecurityRunner.class)
                                                        .run(original, suite, Map.copyOf(inputs))
                                                        .complete())
                                        .isTrue();
                                assertThat(
                                                c.getBean(SecurityRunner.class)
                                                        .assertAcceptable()
                                                        .healthy())
                                        .isTrue();
                                int before = count.get();
                                changed.set(true);
                                var drifted = document(rename ? "orderId" : "id", !rename);
                                var report = policies.assess(drifted, suite, inputs.keySet());
                                assertThat(report.status())
                                        .isEqualTo(
                                                rename
                                                        ? BusinessPolicyReport.Status.INVALID
                                                        : BusinessPolicyReport.Status
                                                                .REVIEW_REQUIRED);
                                assertThat(count).hasValue(before);
                                assertThatThrownBy(
                                                () ->
                                                        c.getBean(BusinessSecurityRunner.class)
                                                                .run(
                                                                        drifted,
                                                                        suite,
                                                                        Map.copyOf(inputs)))
                                        .isInstanceOf(BusinessSecurityException.class);
                                assertThat(count).hasValue(before);
                                assertThat(c.getBean(SecurityRunner.class).run().complete())
                                        .isFalse();
                                assertThatThrownBy(
                                                c.getBean(SecurityRunner.class)::assertAcceptable)
                                        .isInstanceOf(AssertionError.class);
                                assertThat(Files.readAllBytes(policies.reviewFile(suite)))
                                        .isEqualTo(reviewed);
                            });
            // A new run has a fresh collector: the previous failed run must remain failed.
            context(dir, server.getAddress().getPort(), credential)
                    .run(
                            c -> {
                                var current = document(rename ? "orderId" : "id", !rename);
                                var updated = suite(rename ? "/orderId" : "/id");
                                var policies = c.getBean(BusinessPolicyRunner.class);
                                assertThat(
                                                policies.assess(current, updated, inputs.keySet())
                                                        .status())
                                        .isEqualTo(BusinessPolicyReport.Status.REVIEW_REQUIRED);
                                policies.writeReview(current, updated, inputs.keySet());
                                assertThat(
                                                policies.assess(current, updated, inputs.keySet())
                                                        .status())
                                        .isEqualTo(BusinessPolicyReport.Status.VALID);
                                assertThat(
                                                c.getBean(BusinessSecurityRunner.class)
                                                        .run(current, updated, Map.copyOf(inputs))
                                                        .complete())
                                        .isTrue();
                                assertThat(
                                                c.getBean(SecurityRunner.class)
                                                        .assertAcceptable()
                                                        .healthy())
                                        .isTrue();
                            });
            try (var files = Files.walk(dir)) {
                for (Path file : files.filter(Files::isRegularFile).toList())
                    assertThat(Files.readString(file)).doesNotContain(orderId, credential);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void requiringPolicyValidityAlsoRequiresActualBusinessExecution(@TempDir Path dir) {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                TestForgeHttpAutoConfiguration.class,
                                TestForgeSecurityAutoConfiguration.class))
                .withPropertyValues(
                        "forge.security.business.policy.required=true",
                        "forge.security.output-dir=" + dir)
                .run(
                        c -> {
                            assertThat(c).hasNotFailed();
                            assertThat(c).doesNotHaveBean(BusinessSecurityRunner.class);
                            c.getBean(SecurityCollector.class)
                                    .covered("other-block", "run", null, "measured", null);
                            var report = c.getBean(SecurityRunner.class).run();
                            assertThat(report.enabled()).isTrue();
                            assertThat(report.complete()).isFalse();
                            assertThat(report.coverage())
                                    .anyMatch(row -> row.check().equals("block-business"))
                                    .anyMatch(row -> row.check().equals("block-business-policy"));
                            assertThatThrownBy(c.getBean(SecurityRunner.class)::assertAcceptable)
                                    .isInstanceOf(AssertionError.class);
                        });
    }
}
