package io.testforge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.PathParameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.testforge.api.explorer.PreparedRequest;
import io.testforge.artifact.ArtifactSink;
import io.testforge.auth.AuthProperties;
import io.testforge.auth.CredentialPlacement;
import io.testforge.auth.TokenProvider;
import io.testforge.http.HttpProperties;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

class BusinessIdempotencyTest {
    final BusinessSecurityProperties props =
            new BusinessSecurityProperties(
                    true,
                    true,
                    List.of("127.0.0.1"),
                    true,
                    true,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);

    BusinessSecuritySuite.Step write(String id, BusinessSecuritySuite.Phase phase, String alias) {
        return new BusinessSecuritySuite.Step(
                id,
                phase,
                "owner",
                new PreparedRequest(
                        "POST",
                        "/orders/{id}/pay",
                        Map.of("id", "${order}"),
                        Map.of(),
                        "{}",
                        "application/json"),
                Set.of(200),
                List.of(),
                Map.of(),
                List.of(),
                null,
                alias == null ? null : new BusinessIdempotency("Idempotency-Key", alias));
    }

    BusinessSecuritySuite suite(String repeatKey) {
        var verify =
                new BusinessSecuritySuite.Step(
                        "persisted",
                        BusinessSecuritySuite.Phase.VERIFY,
                        "owner",
                        new PreparedRequest(
                                "GET", "/orders/{id}", Map.of("id", "${order}"), Map.of()),
                        Set.of(200),
                        List.of(
                                new BusinessSecuritySuite.Assertion(
                                        "/state",
                                        BusinessSecuritySuite.Match.EQUALS,
                                        TextNode.valueOf("paid"))),
                        Map.of());
        var test =
                new BusinessSecuritySuite.Case(
                        "repeat",
                        BusinessSecuritySuite.Kind.REPLAY,
                        "POST",
                        "/orders/{id}/pay",
                        "owner",
                        List.of(
                                write("first", BusinessSecuritySuite.Phase.CONTROL, "key"),
                                write("again", BusinessSecuritySuite.Phase.PROBE, repeatKey),
                                verify),
                        List.of());
        return new BusinessSecuritySuite(
                "payments", "v1", List.of("owner"), Set.of(test.kind()), List.of(test), List.of());
    }

    void validate(BusinessSecuritySuite suite, Set<String> inputs) {
        new BusinessSecurityValidator(props, role -> true, request -> {})
                .validateCase(
                        suite,
                        suite.cases().getFirst(),
                        Set.of("GET /orders/{id}", "POST /orders/{id}/pay"),
                        inputs);
    }

    @Test
    void replayRequiresTheSameExplicitHeaderAndKeyBinding() {
        validate(suite("key"), Set.of("order", "key"));
        assertThatThrownBy(() -> validate(suite("other"), Set.of("order", "key", "other")))
                .isInstanceOf(BusinessSecurityException.class)
                .hasMessageContaining("identical");
        assertThatThrownBy(() -> validate(suite(null), Set.of("order", "key")))
                .isInstanceOf(BusinessSecurityException.class)
                .hasMessageContaining("identical");
    }

    @Test
    void keyMustBeAvailableBeforeTheRequest() {
        assertThatThrownBy(() -> validate(suite("key"), Set.of("order")))
                .isInstanceOf(BusinessSecurityException.class)
                .hasMessageContaining("binding");
    }

    @Test
    void onlyExplicitIdempotencyHeadersAreAllowedAndCaseIsNormalized() {
        assertThat(new BusinessIdempotency("IDEMPOTENCY-KEY", "key").header())
                .isEqualTo("Idempotency-Key");
        for (String name :
                List.of(
                        "Authorization",
                        "Cookie",
                        "Host",
                        "X-HTTP-Method-Override",
                        "Idempotency-Key\r\nX"))
            assertThatThrownBy(() -> new BusinessIdempotency(name, "key"))
                    .isInstanceOf(BusinessSecurityException.class);
    }

    @Test
    void credentialPlacementCannotBeOverwrittenAndDoesNotFetchTokens() {
        var auth = new AuthProperties(true, null, "Idempotency-Key", null, null, null, null, null);
        var exchange =
                new BusinessSecurityExchange(
                        new HttpProperties(
                                null, null, null, null, null, null, null, null, null, null),
                        null,
                        auth,
                        props,
                        new SecuritySecretRegistry());
        try {
            assertThatThrownBy(
                            () ->
                                    exchange.requireIdempotencyHeader(
                                            new BusinessIdempotency("Idempotency-Key", "key")))
                    .isInstanceOf(BusinessSecurityException.class);
        } finally {
            exchange.close();
        }
        var tokens =
                new TokenProvider(
                        Map.of(
                                "owner",
                                () -> {
                                    throw new AssertionError("must not fetch");
                                }),
                        Map.of("owner", CredentialPlacement.header("X-Idempotency-Key", "")),
                        Clock.systemUTC(),
                        Duration.ZERO);
        var placed =
                new BusinessSecurityExchange(
                        new HttpProperties(
                                null, null, null, null, null, null, null, null, null, null),
                        tokens,
                        null,
                        props,
                        new SecuritySecretRegistry());
        try {
            assertThatThrownBy(
                            () ->
                                    placed.requireIdempotencyHeader(
                                            new BusinessIdempotency("x-idempotency-key", "key")))
                    .isInstanceOf(BusinessSecurityException.class);
        } finally {
            placed.close();
        }
    }

    @Test
    void oldAndNewStepsRoundTripWithoutExposingKeyMaterial() throws Exception {
        var mapper = new ObjectMapper();
        var step = write("first", BusinessSecuritySuite.Phase.CONTROL, "key");
        assertThat(
                        mapper.readValue(
                                mapper.writeValueAsBytes(step), BusinessSecuritySuite.Step.class))
                .isEqualTo(step);
        var legacy =
                new BusinessSecuritySuite.Step(
                        "old",
                        BusinessSecuritySuite.Phase.CONTROL,
                        "owner",
                        step.request(),
                        Set.of(200),
                        List.of(),
                        Map.of());
        assertThat(
                        mapper.readValue(
                                        mapper.writeValueAsBytes(legacy),
                                        BusinessSecuritySuite.Step.class)
                                .idempotency())
                .isNull();
    }

    @Test
    void headerContractAndPlacementChangesRequireReview() {
        var policyProps = new BusinessPolicyProperties(false, null, null, null, null, null);
        var state = new ObjectSchema().addProperty("state", new StringSchema());
        state.setAdditionalProperties(false);
        var response =
                new ApiResponse()
                        .content(
                                new Content()
                                        .addMediaType(
                                                "application/json", new MediaType().schema(state)));
        var read = new Operation().responses(new ApiResponses().addApiResponse("200", response));
        var post =
                new Operation()
                        .responses(new ApiResponses().addApiResponse("200", response))
                        .addParametersItem(
                                new HeaderParameter()
                                        .name("idempotency-key")
                                        .required(true)
                                        .schema(new StringSchema()));
        var doc =
                new OpenAPI()
                        .openapi("3.0.3")
                        .paths(
                                new Paths()
                                        .addPathItem(
                                                "/orders/{id}",
                                                new PathItem()
                                                        .get(read)
                                                        .addParametersItem(
                                                                new PathParameter()
                                                                        .name("id")
                                                                        .required(true)
                                                                        .schema(
                                                                                new StringSchema())))
                                        .addPathItem(
                                                "/orders/{id}/pay",
                                                new PathItem()
                                                        .post(post)
                                                        .addParametersItem(
                                                                new PathParameter()
                                                                        .name("id")
                                                                        .required(true)
                                                                        .schema(
                                                                                new StringSchema()))));
        var suite = suite("key");
        var snapshot = new BusinessContractSnapshotter(policyProps).snapshot(doc, suite);
        assertThat(
                        new BusinessPolicyPreflight(props, policyProps, Set.of("owner"))
                                .check(doc, suite, snapshot, Set.of("order", "key")))
                .noneMatch(
                        issue ->
                                issue.code().startsWith("IDEMPOTENCY_")
                                        || issue.code().equals("PARAMETER_PLACEMENT"));
        var runner =
                new BusinessPolicyRunner(
                        policyProps,
                        props,
                        new SecurityProperties(null, null, null, null, null, null),
                        Set.of("owner"),
                        new SecurityCollector(100),
                        ArtifactSink.NO_OP);
        var changedSteps =
                suite.cases().getFirst().steps().stream()
                        .map(
                                step ->
                                        step.idempotency() == null
                                                ? step
                                                : new BusinessSecuritySuite.Step(
                                                        step.id(),
                                                        step.phase(),
                                                        step.role(),
                                                        step.request(),
                                                        step.statuses(),
                                                        step.assertions(),
                                                        step.captures(),
                                                        step.invariants(),
                                                        step.pagination(),
                                                        new BusinessIdempotency(
                                                                "X-Idempotency-Key", "key")))
                        .toList();
        var changedCase =
                new BusinessSecuritySuite.Case(
                        "repeat",
                        BusinessSecuritySuite.Kind.REPLAY,
                        "POST",
                        "/orders/{id}/pay",
                        "owner",
                        changedSteps,
                        List.of());
        var changed =
                new BusinessSecuritySuite(
                        suite.id(),
                        suite.version(),
                        suite.roles(),
                        suite.checks(),
                        List.of(changedCase),
                        suite.exclusions());
        assertThat(runner.inspect(doc, changed, snapshot, Set.of("order", "key")).issues())
                .anyMatch(issue -> issue.code().equals("IDEMPOTENCY_HEADER_UNDECLARED"))
                .anyMatch(issue -> issue.code().equals("POLICY_STRUCTURE_CHANGED"));
        post.setParameters(List.of());
        var absent = new BusinessContractSnapshotter(policyProps).snapshot(doc, suite);
        assertThat(
                        new BusinessPolicyPreflight(props, policyProps, Set.of("owner"))
                                .check(doc, suite, absent, Set.of("order", "key")))
                .anyMatch(issue -> issue.code().equals("IDEMPOTENCY_HEADER_UNDECLARED"));
    }
}
