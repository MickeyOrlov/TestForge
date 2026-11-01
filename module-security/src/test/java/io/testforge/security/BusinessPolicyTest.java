package io.testforge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

import io.swagger.v3.oas.models.Components;
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
import io.testforge.artifact.ArtifactSink;
import io.testforge.security.BusinessPolicyReport.Status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

class BusinessPolicyTest {
    @TempDir Path dir;
    final ObjectMapper mapper = new ObjectMapper();
    final String planted = "synthetic-private-" + java.util.UUID.randomUUID();
    final SecurityCollector collector = new SecurityCollector(1000);

    BusinessSecurityProperties business() {
        return new BusinessSecurityProperties(
                true, true, List.of("127.0.0.1"), true, true, null, null, null, null, null, null);
    }

    BusinessPolicyProperties properties() {
        return new BusinessPolicyProperties(
                true, dir.resolve("reviews").toString(), null, null, null, null);
    }

    BusinessPolicyRunner runner() {
        return runner(properties());
    }

    BusinessPolicyRunner runner(BusinessPolicyProperties properties) {
        return new BusinessPolicyRunner(
                properties,
                business(),
                new SecurityProperties(
                        null, dir.resolve("reports").toString(), null, null, null, null),
                Set.of("owner", "other"),
                collector,
                ArtifactSink.NO_OP);
    }

    ObjectSchema schema() {
        var schema = new ObjectSchema();
        schema.setAdditionalProperties(false);
        schema.addProperty("id", new StringSchema())
                .addProperty("amount", new io.swagger.v3.oas.models.media.IntegerSchema());
        return schema;
    }

    Operation operation(String id) {
        return new Operation()
                .operationId(id)
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
                                                                                .schema(schema()))))
                                .addApiResponse("403", new ApiResponse().description("denied")));
    }

    OpenAPI document() {
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
                                                .get(operation("order")))
                                .addPathItem(
                                        "/invoices/{id}",
                                        new PathItem()
                                                .addParametersItem(
                                                        new PathParameter()
                                                                .name("id")
                                                                .required(true))
                                                .get(operation("invoice")))
                                .addPathItem("/health", new PathItem().get(operation("health"))));
    }

    BusinessSecuritySuite.Case test(String name, String path, String input) {
        var request = new PreparedRequest("GET", path, Map.of("id", "${" + input + "}"), Map.of());
        var control =
                new BusinessSecuritySuite.Step(
                        "control",
                        BusinessSecuritySuite.Phase.CONTROL,
                        "owner",
                        request,
                        Set.of(200),
                        List.of(),
                        Map.of("saved-" + name, "/id"));
        var probe =
                new BusinessSecuritySuite.Step(
                        "probe",
                        BusinessSecuritySuite.Phase.PROBE,
                        "other",
                        request,
                        Set.of(403),
                        List.of(),
                        Map.of());
        return new BusinessSecuritySuite.Case(
                name,
                BusinessSecuritySuite.Kind.OBJECT_ACCESS,
                "GET",
                path,
                "other",
                List.of(control, probe),
                List.of());
    }

    BusinessSecuritySuite suite() {
        return new BusinessSecuritySuite(
                "orders-policy",
                "v1",
                List.of("other"),
                Set.of(BusinessSecuritySuite.Kind.OBJECT_ACCESS),
                List.of(
                        test("order-case", "/orders/{id}", "order"),
                        test("invoice-case", "/invoices/{id}", "invoice")),
                List.of(
                        new BusinessSecuritySuite.Exclusion(
                                BusinessSecuritySuite.Kind.OBJECT_ACCESS,
                                "GET",
                                "/health",
                                "other",
                                "public-health")));
    }

    Set<String> inputs() {
        return Set.of("order", "invoice");
    }

    io.swagger.v3.oas.models.media.Schema<?> responseSchema(OpenAPI doc, String path) {
        return doc.getPaths()
                .get(path)
                .getGet()
                .getResponses()
                .get("200")
                .getContent()
                .get("application/json")
                .getSchema();
    }

    @Test
    void reviewIsExplicitAssessmentIsReadOnlyAndStaticValidityEarnsNoCoverage() throws Exception {
        var doc = document();
        var runner = runner();
        var suite = suite();
        assertThat(runner.assess(doc, suite, inputs()).status()).isEqualTo(Status.REVIEW_REQUIRED);
        assertThat(runner.reviewFile(suite)).doesNotExist();
        runner.writeReview(doc, suite, inputs());
        byte[] before = Files.readAllBytes(runner.reviewFile(suite));
        assertThat(runner.assess(doc, suite, inputs()).status()).isEqualTo(Status.VALID);
        assertThat(Files.readAllBytes(runner.reviewFile(suite))).isEqualTo(before);
        runner.assertCurrent(doc, suite, inputs());
        assertThat(collector.coverage())
                .noneMatch(coverage -> coverage.status().equals("measured"));
    }

    @Test
    void aUsedSchemaChangeOnlyMarksDependentCasesForReview() {
        var original = document();
        var runner = runner();
        var review = runner.writeReview(original, suite(), inputs());
        var current = document();
        responseSchema(current, "/orders/{id}").addProperty("amount", new StringSchema());
        var report = runner.inspect(current, suite(), review, inputs());
        assertThat(report.status()).isEqualTo(Status.REVIEW_REQUIRED);
        assertThat(report.cases())
                .anyMatch(
                        test ->
                                test.id().equals("order-case")
                                        && test.status() == Status.REVIEW_REQUIRED)
                .anyMatch(
                        test -> test.id().equals("invoice-case") && test.status() == Status.VALID);
    }

    @Test
    void changingExcludedOperationsOrUnusedComponentsDoesNotInvalidateCases() {
        var doc = document();
        var runner = runner();
        var review = runner.writeReview(doc, suite(), inputs());
        responseSchema(doc, "/health").addProperty("build", new StringSchema());
        doc.components(new Components().addSchemas("Unused", new StringSchema().example(planted)));
        assertThat(runner.inspect(doc, suite(), review, inputs()).status()).isEqualTo(Status.VALID);
    }

    @Test
    void removedCapturePointerIsInvalidBeforeRequestsAndCannotBePromoted() throws Exception {
        var doc = document();
        var runner = runner();
        runner.writeReview(doc, suite(), inputs());
        byte[] before = Files.readAllBytes(runner.reviewFile(suite()));
        responseSchema(doc, "/orders/{id}").getProperties().remove("id");
        responseSchema(doc, "/orders/{id}").addProperty("orderId", new StringSchema());
        var report = runner.assess(doc, suite(), inputs());
        assertThat(report.status()).isEqualTo(Status.INVALID);
        assertThat(report.issues())
                .anyMatch(
                        issue ->
                                issue.code().equals("POINTER_MISSING")
                                        && "control".equals(issue.stepId()));
        assertThatThrownBy(() -> runner.writeReview(doc, suite(), inputs()))
                .isInstanceOf(BusinessSecurityException.class);
        assertThat(Files.readAllBytes(runner.reviewFile(suite()))).isEqualTo(before);
    }

    @Test
    void changedLinkBindingsAndSecuritySchemePlacementInvalidateTheirSourceCases() {
        var doc = document();
        doc.getPaths()
                .get("/orders/{id}")
                .getGet()
                .getResponses()
                .get("200")
                .addLink(
                        "invoice",
                        new io.swagger.v3.oas.models.links.Link()
                                .operationId("invoice")
                                .parameters("id", "$response.body#/id"));
        var runner = runner();
        var review = runner.writeReview(doc, suite(), inputs());
        doc.getPaths()
                .get("/orders/{id}")
                .getGet()
                .getResponses()
                .get("200")
                .getLinks()
                .get("invoice")
                .parameters("id", "$response.body#/otherId");
        assertThat(runner.inspect(doc, suite(), review, inputs()).cases())
                .anyMatch(
                        test ->
                                test.id().equals("order-case")
                                        && test.status() == Status.REVIEW_REQUIRED);
        doc = document();
        doc.components(
                new Components()
                        .addSecuritySchemes(
                                "access",
                                new io.swagger.v3.oas.models.security.SecurityScheme()
                                        .type(
                                                io.swagger.v3.oas.models.security.SecurityScheme
                                                        .Type.HTTP)
                                        .scheme("bearer")));
        doc.getPaths()
                .get("/orders/{id}")
                .getGet()
                .addSecurityItem(
                        new io.swagger.v3.oas.models.security.SecurityRequirement()
                                .addList("access"));
        review = runner.writeReview(doc, suite(), inputs());
        doc.getComponents()
                .getSecuritySchemes()
                .get("access")
                .type(io.swagger.v3.oas.models.security.SecurityScheme.Type.APIKEY)
                .in(io.swagger.v3.oas.models.security.SecurityScheme.In.HEADER)
                .name("X-Key");
        assertThat(runner.inspect(doc, suite(), review, inputs()).status())
                .isEqualTo(Status.REVIEW_REQUIRED);
    }

    @Test
    void missingAndFutureBindingsFailBeforeAnyFixturePreparation() {
        var report = runner().assess(document(), suite(), Set.of());
        assertThat(report.status()).isEqualTo(Status.INVALID);
        assertThat(report.issues())
                .anyMatch(
                        issue ->
                                issue.code().equals("BINDING_UNAVAILABLE")
                                        && "control".equals(issue.stepId()));
    }

    @Test
    void newlyRequiredQueryParametersAndRemovedOperationsAreInvalid() {
        var doc = document();
        var runner = runner();
        runner.writeReview(doc, suite(), inputs());
        doc.getPaths()
                .get("/orders/{id}")
                .getGet()
                .addParametersItem(
                        new io.swagger.v3.oas.models.parameters.QueryParameter()
                                .name("tenant")
                                .required(true));
        assertThat(runner.assess(doc, suite(), inputs()).issues())
                .anyMatch(issue -> issue.code().equals("QUERY_PARAMETER_MISSING"));
        doc.getPaths().remove("/orders/{id}");
        assertThat(runner.assess(doc, suite(), inputs()).status()).isEqualTo(Status.INVALID);
    }

    @Test
    void newOperationsRequireAnExplicitCoverageDecision() {
        var doc = document();
        var runner = runner();
        runner.writeReview(doc, suite(), inputs());
        doc.getPaths().addPathItem("/payments", new PathItem().get(operation("payments")));
        var report = runner.assess(doc, suite(), inputs());
        assertThat(report.status()).isEqualTo(Status.REVIEW_REQUIRED);
        assertThat(report.issues()).anyMatch(issue -> issue.code().equals("COVERAGE_UNDECLARED"));
        assertThatThrownBy(() -> runner.writeReview(doc, suite(), inputs()))
                .isInstanceOf(BusinessSecurityException.class);
    }

    @Test
    void reviewMetadataAndReportsContainNeitherSourceNorPolicyLiteralValues() throws Exception {
        var doc = document();
        responseSchema(doc, "/orders/{id}")
                .example(Map.of("secret", planted))
                .setDefault(Map.of("secret", planted));
        responseSchema(doc, "/orders/{id}")
                .addProperty("secret", new StringSchema()._enum(List.of(planted)));
        var original = suite();
        var cases = new ArrayList<>(original.cases());
        var test = cases.getFirst();
        var steps = new ArrayList<>(test.steps());
        var control = steps.getFirst();
        steps.set(
                0,
                new BusinessSecuritySuite.Step(
                        control.id(),
                        control.phase(),
                        control.role(),
                        control.request(),
                        control.statuses(),
                        List.of(
                                new BusinessSecuritySuite.Assertion(
                                        "/secret",
                                        BusinessSecuritySuite.Match.EQUALS,
                                        TextNode.valueOf(planted))),
                        control.captures()));
        cases.set(
                0,
                new BusinessSecuritySuite.Case(
                        test.id(),
                        test.kind(),
                        test.method(),
                        test.path(),
                        test.role(),
                        steps,
                        test.cleanup()));
        var suite =
                new BusinessSecuritySuite(
                        original.id(),
                        original.version(),
                        original.roles(),
                        original.checks(),
                        cases,
                        original.exclusions());
        runner().writeReview(doc, suite, inputs());
        try (var files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList())
                assertThat(Files.readString(file)).doesNotContain(planted);
        }
    }

    @Test
    void corruptUnknownAndOversizedReviewsFailClosedWithoutRewritingThem() throws Exception {
        var runner = runner();
        var doc = document();
        runner.writeReview(doc, suite(), inputs());
        Path file = runner.reviewFile(suite());
        Files.writeString(file, "not-json-" + planted);
        assertThat(runner.assess(doc, suite(), inputs()).status()).isEqualTo(Status.INVALID);
        assertThat(Files.readString(file)).isEqualTo("not-json-" + planted);
        runner.writeReview(doc, suite(), inputs());
        var tree = mapper.readTree(file.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree).put("formatVersion", 999);
        mapper.writeValue(file.toFile(), tree);
        assertThat(runner.assess(doc, suite(), inputs()).issues())
                .anyMatch(issue -> issue.code().equals("REVIEW_FORMAT"));
        var limited =
                runner(
                        new BusinessPolicyProperties(
                                true, dir.resolve("reviews").toString(), null, null, null, 16));
        assertThat(limited.assess(doc, suite(), inputs()).status()).isEqualTo(Status.INVALID);
    }

    @Test
    void localRecursiveSchemasAreBoundedAndUnusedSchemaDefinitionsAreNotStored() {
        var doc = document();
        var order = schema();
        order.addProperty(
                "parent",
                new io.swagger.v3.oas.models.media.Schema<>().$ref("#/components/schemas/Order"));
        doc.components(
                new Components()
                        .addSchemas("Order", order)
                        .addSchemas("Unused", new StringSchema().example(planted)));
        doc.getPaths()
                .get("/orders/{id}")
                .getGet()
                .getResponses()
                .get("200")
                .getContent()
                .get("application/json")
                .schema(
                        new io.swagger.v3.oas.models.media.Schema<>()
                                .$ref("#/components/schemas/Order"));
        var review = runner().writeReview(doc, suite(), inputs());
        assertThat(review.operations().get("GET /orders/{id}").path("components").has("Order"))
                .isTrue();
        assertThat(review.operations().get("GET /orders/{id}").path("components").has("Unused"))
                .isFalse();
        assertThat(runner().inspect(doc, suite(), review, inputs()).status())
                .isEqualTo(Status.VALID);
        assertThat(
                        runner(
                                        new BusinessPolicyProperties(
                                                true,
                                                dir.resolve("reviews").toString(),
                                                null,
                                                1,
                                                null,
                                                null))
                                .assess(doc, suite(), inputs())
                                .status())
                .isEqualTo(Status.INVALID);
    }

    BusinessSecuritySuite replaceFirst(BusinessSecuritySuite.Case test) {
        var original = suite();
        return new BusinessSecuritySuite(
                original.id(),
                original.version(),
                original.roles(),
                original.checks(),
                List.of(test, original.cases().get(1)),
                original.exclusions());
    }

    @Test
    void assertionsCannotUseCapturesFromTheirOwnOrLaterSteps() {
        var test = suite().cases().getFirst();
        var steps = new ArrayList<>(test.steps());
        var control = steps.getFirst();
        steps.set(
                0,
                new BusinessSecuritySuite.Step(
                        control.id(),
                        control.phase(),
                        control.role(),
                        control.request(),
                        control.statuses(),
                        List.of(
                                new BusinessSecuritySuite.Assertion(
                                        "/id",
                                        BusinessSecuritySuite.Match.EQUALS,
                                        TextNode.valueOf("${saved-order-case}"))),
                        control.captures()));
        var policy =
                replaceFirst(
                        new BusinessSecuritySuite.Case(
                                test.id(),
                                test.kind(),
                                test.method(),
                                test.path(),
                                test.role(),
                                steps,
                                test.cleanup()));
        var report = runner().assess(document(), policy, inputs());
        assertThat(report.status()).isEqualTo(Status.INVALID);
        assertThat(report.issues())
                .anyMatch(
                        issue ->
                                issue.code().equals("BINDING_UNAVAILABLE")
                                        && "saved-order-case".equals(issue.location()));
    }

    @Test
    void cleanupCannotDependOnProbeCapturesEvenIfTheHappyPathWouldProduceThem() {
        var doc = document();
        doc.getPaths()
                .get("/orders/{id}")
                .delete(
                        new Operation()
                                .responses(
                                        new ApiResponses()
                                                .addApiResponse("204", new ApiResponse())));
        var test = suite().cases().getFirst();
        var steps = new ArrayList<>(test.steps());
        var probe = steps.get(1);
        steps.set(
                1,
                new BusinessSecuritySuite.Step(
                        probe.id(),
                        probe.phase(),
                        probe.role(),
                        probe.request(),
                        probe.statuses(),
                        List.of(),
                        Map.of("from-probe", "")));
        var cleanup =
                new BusinessSecuritySuite.Step(
                        "cleanup",
                        BusinessSecuritySuite.Phase.CONTROL,
                        "owner",
                        new PreparedRequest(
                                "DELETE", "/orders/{id}", Map.of("id", "${from-probe}"), Map.of()),
                        Set.of(204),
                        List.of(),
                        Map.of());
        var policy =
                replaceFirst(
                        new BusinessSecuritySuite.Case(
                                test.id(),
                                test.kind(),
                                test.method(),
                                test.path(),
                                test.role(),
                                steps,
                                List.of(cleanup)));
        assertThat(runner().assess(doc, policy, inputs()).issues())
                .anyMatch(issue -> issue.code().equals("CLEANUP_BINDING"));
    }

    @Test
    void missingUnknownRolesAndPartialInterpolationAreInvalid() {
        var original = suite();
        var test = original.cases().getFirst();
        var control = test.steps().getFirst();
        var steps = new ArrayList<>(test.steps());
        steps.set(
                0,
                new BusinessSecuritySuite.Step(
                        control.id(),
                        control.phase(),
                        "unconfigured",
                        control.request(),
                        control.statuses(),
                        List.of(),
                        control.captures()));
        var changed =
                replaceFirst(
                        new BusinessSecuritySuite.Case(
                                test.id(),
                                test.kind(),
                                test.method(),
                                test.path(),
                                test.role(),
                                steps,
                                test.cleanup()));
        assertThat(runner().assess(document(), changed, inputs()).issues())
                .anyMatch(issue -> issue.code().equals("UNKNOWN_ROLE"));
        var request =
                new PreparedRequest(
                        "GET", "/orders/{id}", Map.of("id", "prefix-${order}"), Map.of());
        steps = new ArrayList<>(test.steps());
        steps.set(
                0,
                new BusinessSecuritySuite.Step(
                        control.id(),
                        control.phase(),
                        control.role(),
                        request,
                        control.statuses(),
                        List.of(),
                        control.captures()));
        changed =
                replaceFirst(
                        new BusinessSecuritySuite.Case(
                                test.id(),
                                test.kind(),
                                test.method(),
                                test.path(),
                                test.role(),
                                steps,
                                test.cleanup()));
        assertThat(runner().assess(document(), changed, inputs()).issues())
                .anyMatch(issue -> issue.code().equals("PARTIAL_BINDING"));
    }

    @Test
    void changedExpectedStatusesAndPolicyVersionNeedExplicitReview() {
        var runner = runner();
        var review = runner.writeReview(document(), suite(), inputs());
        var test = suite().cases().getFirst();
        var probe = test.steps().get(1);
        var changedProbe =
                new BusinessSecuritySuite.Step(
                        probe.id(),
                        probe.phase(),
                        probe.role(),
                        probe.request(),
                        Set.of(404),
                        List.of(),
                        Map.of());
        var changed =
                replaceFirst(
                        new BusinessSecuritySuite.Case(
                                test.id(),
                                test.kind(),
                                test.method(),
                                test.path(),
                                test.role(),
                                List.of(test.steps().getFirst(), changedProbe),
                                List.of()));
        assertThat(runner.inspect(document(), changed, review, inputs()).issues())
                .anyMatch(issue -> issue.code().equals("POLICY_STRUCTURE_CHANGED"));
        var version =
                new BusinessSecuritySuite(
                        changed.id(),
                        "v2",
                        changed.roles(),
                        changed.checks(),
                        changed.cases(),
                        changed.exclusions());
        assertThat(runner.assess(document(), version, inputs()).status())
                .isEqualTo(Status.REVIEW_REQUIRED);
        assertThat(runner.reviewFile(version)).doesNotExist();
    }

    @Test
    void aForbiddenUndocumentedFieldCanBeAssertedAbsentButPositivePointersNeedSchemas() {
        var original = suite();
        var test = original.cases().getFirst();
        var control = test.steps().getFirst();
        var steps = new ArrayList<>(test.steps());
        steps.set(
                0,
                new BusinessSecuritySuite.Step(
                        control.id(),
                        control.phase(),
                        control.role(),
                        control.request(),
                        control.statuses(),
                        List.of(
                                new BusinessSecuritySuite.Assertion(
                                        "/privateField", BusinessSecuritySuite.Match.ABSENT, null)),
                        control.captures()));
        var policy =
                replaceFirst(
                        new BusinessSecuritySuite.Case(
                                test.id(),
                                test.kind(),
                                test.method(),
                                test.path(),
                                test.role(),
                                steps,
                                test.cleanup()));
        var runner = runner();
        runner.writeReview(document(), policy, inputs());
        var doc = document();
        doc.getPaths().get("/orders/{id}").getGet().getResponses().get("200").content(null);
        assertThat(runner.assess(doc, policy, inputs()).issues())
                .anyMatch(issue -> issue.code().equals("JSON_SCHEMA_UNKNOWN"));
    }

    @Test
    void schemaFieldsNamedRefAreNotConfusedWithComponentReferences() {
        var doc = document();
        var order = schema();
        order.addProperty("ref", new StringSchema());
        doc.components(new Components().addSchemas("Order", order));
        for (String path : List.of("/orders/{id}", "/invoices/{id}"))
            doc.getPaths()
                    .get(path)
                    .getGet()
                    .getResponses()
                    .get("200")
                    .getContent()
                    .get("application/json")
                    .schema(
                            new io.swagger.v3.oas.models.media.Schema<>()
                                    .$ref("#/components/schemas/Order"));
        var runner = runner();
        runner.writeReview(doc, suite(), inputs());
        assertThat(runner.assess(doc, suite(), inputs()).status()).isEqualTo(Status.VALID);
    }
}
