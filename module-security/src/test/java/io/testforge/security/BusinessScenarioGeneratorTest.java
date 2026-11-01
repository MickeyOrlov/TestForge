package io.testforge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.links.Link;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.parameters.PathParameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.testforge.artifact.ArtifactSink;
import io.testforge.http.TestForgeHttpAutoConfiguration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class BusinessScenarioGeneratorTest {
    @TempDir Path dir;
    final ObjectMapper mapper = new ObjectMapper();
    final String planted = "synthetic-spec-value-" + java.util.UUID.randomUUID();

    BusinessScenarioGenerator generator(BusinessScenarioProperties properties) {
        return new BusinessScenarioGenerator(
                properties,
                new SecurityProperties(null, dir.toString(), null, null, null, null),
                ArtifactSink.NO_OP);
    }

    BusinessScenarioProperties defaults() {
        return new BusinessScenarioProperties(null, null, null, null, null);
    }

    OpenAPI document() {
        var schema = new ObjectSchema().example(Map.of("secret", planted));
        var body =
                new RequestBody()
                        .content(
                                new Content()
                                        .addMediaType(
                                                "application/json",
                                                new MediaType().schema(schema)));
        var create =
                new Operation()
                        .operationId("createOrder")
                        .requestBody(body)
                        .responses(
                                new ApiResponses()
                                        .addApiResponse(
                                                "201",
                                                new ApiResponse()
                                                        .description(planted)
                                                        .addLink(
                                                                "read",
                                                                new Link()
                                                                        .operationId("readOrder")
                                                                        .parameters(
                                                                                "id",
                                                                                "$response.body#/id"))));
        return new OpenAPI()
                .openapi("3.0.3")
                .addServersItem(
                        new io.swagger.v3.oas.models.servers.Server()
                                .url("https://ignored.example.invalid/" + planted))
                .paths(
                        new Paths()
                                .addPathItem("/orders", new PathItem().post(create))
                                .addPathItem(
                                        "/orders/{id}",
                                        new PathItem()
                                                .addParametersItem(
                                                        new PathParameter()
                                                                .name("id")
                                                                .required(true))
                                                .get(new Operation().operationId("readOrder"))
                                                .patch(
                                                        new Operation()
                                                                .operationId("updateOrder")
                                                                .requestBody(body))
                                                .delete(
                                                        new Operation()
                                                                .operationId("deleteOrder"))));
    }

    BusinessScenarioSeed seed() {
        return new BusinessScenarioSeed(
                "protect-price",
                "v1",
                BusinessSecuritySuite.Kind.PROPERTY_WRITE,
                "POST /orders",
                "PATCH /orders/{id}",
                "owner",
                "owner",
                List.of(),
                "DELETE /orders/{id}");
    }

    Link readLink(OpenAPI doc) {
        return doc.getPaths()
                .get("/orders")
                .getPost()
                .getResponses()
                .get("201")
                .getLinks()
                .get("read");
    }

    @Test
    void linksAndPathHintsProduceEditableDraftsWithCapturesButNoInferredPolicy() throws Exception {
        var report = generator(defaults()).generate(document(), List.of(seed()));
        var draft = report.drafts().getFirst();
        assertThat(draft.status()).isEqualTo("needs_review");
        assertThat(draft.chain())
                .containsExactly("POST /orders", "GET /orders/{id}", "PATCH /orders/{id}");
        assertThat(draft.suite().at("/cases/0/steps/0/captures").size()).isEqualTo(1);
        assertThat(draft.suite().at("/cases/0/steps/3/request/pathParameters/id").asText())
                .isEqualTo("${capture_step0_0}");
        assertThat(draft.suite().at("/cases/0/steps/3/statuses")).isEmpty();
        assertThat(draft.needs()).anyMatch(n -> n.kind().equals("assertions"));
        assertThatThrownBy(() -> mapper.treeToValue(draft.suite(), BusinessSecuritySuite.class))
                .isInstanceOf(Exception.class);
        try (var files = Files.list(dir.resolve("scenario-drafts"))) {
            for (Path file : files.toList())
                assertThat(Files.readString(file))
                        .doesNotContain(planted, "ignored.example.invalid");
        }
    }

    @Test
    void localOperationRefsAndComponentLinksResolveWithoutUriFetches() {
        var doc = document();
        var shared =
                new Link()
                        .operationRef("#/paths/~1orders~1{id}/get")
                        .parameters("path.id", "$response.body#/id");
        doc.components(new Components().addLinks("shared", shared));
        doc.getPaths()
                .get("/orders")
                .getPost()
                .getResponses()
                .get("201")
                .getLinks()
                .put("read", new Link().$ref("#/components/links/shared"));
        assertThat(
                        generator(defaults())
                                .generate(doc, List.of(seed()))
                                .drafts()
                                .getFirst()
                                .status())
                .isEqualTo("needs_review");
    }

    @Test
    void externalReferencesAndServerOverridesBlockChainsRatherThanFollowingThem() {
        var doc = document();
        readLink(doc).operationId(null).operationRef("https://ignored.example.invalid/" + planted);
        assertThat(
                        generator(defaults())
                                .generate(doc, List.of(seed()))
                                .drafts()
                                .getFirst()
                                .status())
                .isEqualTo("blocked");
        doc = document();
        readLink(doc)
                .server(
                        new io.swagger.v3.oas.models.servers.Server()
                                .url("https://ignored.example.invalid"));
        assertThat(
                        generator(defaults())
                                .generate(doc, List.of(seed()))
                                .drafts()
                                .getFirst()
                                .status())
                .isEqualTo("blocked");
    }

    @Test
    void cyclicComponentLinksAndAmbiguousOperationIdsDoNotGuessTargets() {
        var doc = document();
        doc.components(
                new Components()
                        .addLinks("a", new Link().$ref("#/components/links/b"))
                        .addLinks("b", new Link().$ref("#/components/links/a")));
        doc.getPaths()
                .get("/orders")
                .getPost()
                .getResponses()
                .get("201")
                .getLinks()
                .put("read", new Link().$ref("#/components/links/a"));
        assertThat(
                        generator(defaults())
                                .generate(doc, List.of(seed()))
                                .drafts()
                                .getFirst()
                                .status())
                .isEqualTo("blocked");
        doc = document();
        doc.getPaths()
                .addPathItem(
                        "/other", new PathItem().get(new Operation().operationId("readOrder")));
        assertThat(
                        generator(defaults())
                                .generate(doc, List.of(seed()))
                                .drafts()
                                .getFirst()
                                .status())
                .isEqualTo("blocked");
    }

    @Test
    void literalsAndUnsupportedRuntimeExpressionsBecomeProjectInputsWithoutCopyingValues()
            throws Exception {
        var doc = document();
        readLink(doc).parameters("id", planted);
        var report = generator(defaults()).generate(doc, List.of(seed()));
        assertThat(report.links())
                .anyMatch(link -> "link-parameter-needs-project-binding".equals(link.reason()));
        assertThat(report.drafts().getFirst().needs())
                .anyMatch(
                        need ->
                                need.binding() != null
                                        && need.binding().equals("input_step1_path_id"));
        assertThat(mapper.writeValueAsString(report)).doesNotContain(planted);
        doc = document();
        readLink(doc).parameters("id", "$response.header.Location");
        assertThat(generator(defaults()).generate(doc, List.of(seed())).links())
                .anyMatch(
                        link -> link.status().equals("needs_review") && link.bindings().isEmpty());
    }

    @Test
    void multipleResponseBranchesAreAmbiguousAndReplaceAnyPreviousDraftFile() throws Exception {
        var doc = document();
        generator(defaults()).generate(doc, List.of(seed()));
        doc.getPaths()
                .get("/orders")
                .getPost()
                .getResponses()
                .addApiResponse(
                        "202",
                        new ApiResponse()
                                .addLink(
                                        "read",
                                        new Link()
                                                .operationId("readOrder")
                                                .parameters("id", "$response.body#/otherId")));
        assertThat(
                        generator(defaults())
                                .generate(doc, List.of(seed()))
                                .drafts()
                                .getFirst()
                                .status())
                .isEqualTo("blocked");
        assertThat(
                        mapper.readTree(
                                        dir.resolve("scenario-drafts/protect-price-draft.json")
                                                .toFile())
                                .path("status")
                                .asText())
                .isEqualTo("blocked");
    }

    @Test
    void explicitWaypointsResolveDifferentIntermediateOperationChoices() {
        var doc = document();
        doc.getPaths()
                .addPathItem(
                        "/alternate/{id}",
                        new PathItem()
                                .addParametersItem(new PathParameter().name("id").required(true))
                                .get(
                                        new Operation()
                                                .operationId("alternate")
                                                .responses(
                                                        new ApiResponses()
                                                                .addApiResponse(
                                                                        "200",
                                                                        new ApiResponse()
                                                                                .addLink(
                                                                                        "update",
                                                                                        new Link()
                                                                                                .operationId(
                                                                                                        "updateOrder")
                                                                                                .parameters(
                                                                                                        "id",
                                                                                                        "$request.path.id"))))));
        doc.getPaths()
                .get("/orders")
                .getPost()
                .getResponses()
                .get("201")
                .addLink(
                        "alternate",
                        new Link().operationId("alternate").parameters("id", "$response.body#/id"));
        assertThat(
                        generator(defaults())
                                .generate(doc, List.of(seed()))
                                .drafts()
                                .getFirst()
                                .status())
                .isEqualTo("blocked");
        var seed =
                new BusinessScenarioSeed(
                        "chosen",
                        "v1",
                        seed().kind(),
                        seed().setup(),
                        seed().probe(),
                        "owner",
                        "owner",
                        List.of("GET /orders/{id}"),
                        seed().cleanup());
        assertThat(generator(defaults()).generate(doc, List.of(seed)).drafts().getFirst().status())
                .isEqualTo("needs_review");
    }

    @Test
    void limitsFailClosedAndPathHintsCanBeDisabled() {
        assertThatThrownBy(
                        () ->
                                generator(new BusinessScenarioProperties(1, null, null, null, null))
                                        .generate(document(), List.of(seed())))
                .hasMessageContaining("operation count");
        assertThatThrownBy(
                        () ->
                                generator(new BusinessScenarioProperties(null, 1, null, null, null))
                                        .generate(document(), List.of(seed())))
                .hasMessageContaining("max-links");
        assertThat(
                        generator(new BusinessScenarioProperties(null, null, 1, null, null))
                                .generate(document(), List.of(seed()))
                                .drafts()
                                .getFirst()
                                .status())
                .isEqualTo("blocked");
        assertThat(
                        generator(new BusinessScenarioProperties(null, null, null, null, false))
                                .generate(document(), List.of(seed()))
                                .drafts()
                                .getFirst()
                                .status())
                .isEqualTo("blocked");
    }

    @Test
    void writesRequireExplicitMatchingCleanupAndTenantAliasesRemainUnfilled() {
        var original = seed();
        var missing =
                new BusinessScenarioSeed(
                        original.id(),
                        "v1",
                        original.kind(),
                        original.setup(),
                        original.probe(),
                        "owner",
                        "owner",
                        List.of(),
                        null);
        assertThat(
                        generator(defaults())
                                .generate(document(), List.of(missing))
                                .drafts()
                                .getFirst()
                                .status())
                .isEqualTo("blocked");
        var tenant =
                new BusinessScenarioSeed(
                        "tenant",
                        "v1",
                        BusinessSecuritySuite.Kind.TENANT_ACCESS,
                        "GET /orders/{id}",
                        "GET /orders/{id}",
                        "owner",
                        "foreign",
                        List.of(),
                        null);
        var draft = generator(defaults()).generate(document(), List.of(tenant)).drafts().getFirst();
        assertThat(draft.suite().path("tenants")).isEmpty();
        assertThat(draft.needs()).anyMatch(n -> n.kind().equals("tenant-policy"));
    }

    @Test
    void requiredUnsupportedHeadersBlockRatherThanGenerateUnsatisfiableRequests() {
        var doc = document();
        doc.getPaths()
                .get("/orders")
                .getPost()
                .addParametersItem(
                        new io.swagger.v3.oas.models.parameters.HeaderParameter()
                                .name("X-Tenant")
                                .required(true));
        assertThat(
                        generator(defaults())
                                .generate(doc, List.of(seed()))
                                .drafts()
                                .getFirst()
                                .status())
                .isEqualTo("blocked");
    }

    @Test
    void generationDoesNotTurnOnTrafficOrEarnSecurityCoverage() {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                TestForgeHttpAutoConfiguration.class,
                                TestForgeSecurityAutoConfiguration.class))
                .withPropertyValues("forge.security.output-dir=" + dir)
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .doesNotHaveBean(BusinessSecurityRunner.class);
                            context.getBean(BusinessScenarioGenerator.class)
                                    .generate(document(), List.of(seed()));
                            assertThat(context.getBean(SecurityCollector.class).coverage())
                                    .isEmpty();
                            assertThat(context.getBean(SecurityRunner.class).run().measured())
                                    .isFalse();
                        });
    }

    @Test
    void requestQueryBindingsPropagateWithoutLosingTheSharedFixture() {
        var doc = document();
        doc.getPaths()
                .get("/orders/{id}")
                .addParametersItem(
                        new io.swagger.v3.oas.models.parameters.QueryParameter()
                                .name("tenant")
                                .required(true));
        readLink(doc).parameters("query.tenant", "$response.body#/tenantId");
        var report = generator(defaults()).generate(doc, List.of(seed()));
        assertThat(report.drafts().getFirst().status()).isEqualTo("needs_review");
        var request = report.drafts().getFirst().suite().at("/cases/0/steps/3/request");
        assertThat(request.at("/queryParameters/tenant").asText()).startsWith("${capture_step0_");
        assertThat(report.links())
                .anyMatch(
                        link ->
                                link.bindings().stream()
                                        .anyMatch(binding -> binding.location().equals("query")));
    }

    @Test
    void responseBodyLinksGenerateCapturesWhileLiteralBodiesNeverEnterArtifacts() throws Exception {
        var doc = document();
        var update =
                new Link()
                        .operationId("updateOrder")
                        .parameters("id", "$response.body#/id")
                        .requestBody("$response.body#/patch");
        doc.getPaths().get("/orders").getPost().getResponses().get("201").addLink("update", update);
        var draft = generator(defaults()).generate(doc, List.of(seed())).drafts().getFirst();
        assertThat(draft.status()).isEqualTo("needs_review");
        assertThat(draft.chain()).containsExactly("POST /orders", "PATCH /orders/{id}");
        assertThat(draft.suite().at("/cases/0/steps/0/captures").toString()).contains("/patch");
        update.requestBody(Map.of("secret", planted));
        var report = generator(defaults()).generate(doc, List.of(seed()));
        assertThat(mapper.writeValueAsString(report)).doesNotContain(planted);
        assertThat(report.links())
                .anyMatch(link -> "link-body-needs-project-binding".equals(link.reason()));
    }

    @Test
    void replayDraftContainsAnIdenticalSuccessfulFirstRequestBeforeTheCheckpoint() {
        var doc = document();
        doc.getPaths()
                .addPathItem(
                        "/orders/{id}/refund",
                        new PathItem()
                                .addParametersItem(new PathParameter().name("id").required(true))
                                .post(new Operation().operationId("refund")));
        var replay =
                new BusinessScenarioSeed(
                        "replay",
                        "v1",
                        BusinessSecuritySuite.Kind.REPLAY,
                        "GET /orders/{id}",
                        "POST /orders/{id}/refund",
                        "owner",
                        "owner",
                        List.of(),
                        "DELETE /orders/{id}");
        var draft = generator(defaults()).generate(doc, List.of(replay)).drafts().getFirst();
        assertThat(draft.status()).isEqualTo("needs_review");
        var steps = draft.suite().at("/cases/0/steps");
        assertThat(steps.get(1).path("id").asText()).isEqualTo("first-success");
        assertThat(steps.get(1).path("request")).isEqualTo(steps.get(3).path("request"));
        assertThat(steps.get(2).path("captures").has("before")).isTrue();
    }

    @Test
    void nonJsonBodiesAndLiteralIdentifierPathsAreNotMisrepresentedAsSupported() {
        var doc = document();
        doc.getPaths()
                .get("/orders")
                .getPost()
                .requestBody(
                        new RequestBody()
                                .content(
                                        new Content()
                                                .addMediaType(
                                                        "multipart/form-data", new MediaType())));
        assertThat(
                        generator(defaults())
                                .generate(doc, List.of(seed()))
                                .drafts()
                                .getFirst()
                                .status())
                .isEqualTo("blocked");
        doc = document();
        doc.getPaths().addPathItem("/orders/123456", new PathItem().get(new Operation()));
        final var invalid = doc;
        assertThatThrownBy(() -> generator(defaults()).generate(invalid, List.of(seed())))
                .hasMessageContaining("structural paths");
    }
}
