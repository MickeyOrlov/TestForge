package io.testforge.api.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

/**
 * The baseline carries every body case: a case claims one field is wrong, and
 * that claim is only true if everything else was right.
 */
class JsonBodyFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonBodyFactory factory = new JsonBodyFactory(objectMapper);

    @Test
    void buildsABodySatisfyingEveryDeclaredConstraint() {
        JsonNode body = baseline("createUser");

        assertThat(body.path("name").asText()).hasSizeBetween(2, 10);
        assertThat(body.path("age").asInt()).isBetween(18, 120);
        assertThat(body.path("active").isBoolean()).isTrue();
        assertThat(body.path("role").asText()).isEqualTo("admin");
        assertThat(body.path("tags").isArray()).isTrue();
        assertThat(body.path("tags")).hasSize(1);
    }

    @Test
    void nestedObjectsAreBuiltToo() {
        JsonNode profile = baseline("createUser").path("profile");

        assertThat(profile.isObject()).isTrue();
        assertThat(profile.path("city").asText()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void exclusiveBoundsAndMultipleOfAreBothRespected() {
        // score is > 0 and a multiple of 5, so 0 is invalid and 5 is the first
        // value that satisfies both
        double score = baseline("createUser").path("profile").path("score").asDouble();

        assertThat(score).isGreaterThan(0);
        assertThat(score % 5).isZero();
    }

    @Test
    void optionalFieldsAreIncludedSoTheyCanBeFuzzedAtAll() {
        assertThat(baseline("createUser").has("nickname")).isTrue();
    }

    @Test
    void generationIsDeterministic() {
        assertThat(baseline("createUser").toString()).isEqualTo(baseline("createUser").toString());
    }

    @Test
    void aSchemaNoValueCanSatisfyIsReportedRatherThanGuessedAt() {
        JsonBodyFactory.Baseline baseline = factory.build(schema("impossibleBody"));

        assertThat(baseline.usable()).isFalse();
        assertThat(baseline.unsupportedReason())
                .contains("pattern")
                .contains("$.code");
    }

    @Test
    void aBranchingRootSchemaIsUnsupportedRatherThanHalfGuessed() {
        JsonBodyFactory.Baseline baseline = factory.build(schema("eitherBody"));

        assertThat(baseline.usable()).isFalse();
        assertThat(baseline.unsupportedReason()).contains("oneOf/anyOf");
    }

    @Test
    void allOfIsMergedIntoOneObject() {
        Schema<Object> first = new Schema<>();
        first.setType("object");
        first.setProperties(new java.util.LinkedHashMap<>(java.util.Map.of("a", new Schema<>().type("string"))));
        first.setRequired(java.util.List.of("a"));

        Schema<Object> second = new Schema<>();
        second.setType("object");
        second.setProperties(new java.util.LinkedHashMap<>(java.util.Map.of("b", new Schema<>().type("integer"))));

        Schema<Object> composed = new Schema<>();
        composed.setAllOf(java.util.List.of(first, second));

        JsonBodyFactory.Baseline baseline = factory.build(composed);

        assertThat(baseline.usable()).isTrue();
        assertThat(baseline.body().has("a")).isTrue();
        assertThat(baseline.body().has("b")).isTrue();
    }

    private JsonNode baseline(String operationId) {
        JsonBodyFactory.Baseline baseline = factory.build(schema(operationId));
        assertThat(baseline.unsupportedReason()).isNull();
        return baseline.body();
    }

    private Schema<?> schema(String operationId) {
        return FuzzFixtures.operation(operationId).operation()
                .getRequestBody().getContent().get("application/json").getSchema();
    }
}
