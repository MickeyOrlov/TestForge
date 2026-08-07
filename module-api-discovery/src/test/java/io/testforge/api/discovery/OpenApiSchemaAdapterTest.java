package io.testforge.api.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.contract.ContractProperties;
import io.testforge.contract.json.ContractMappers;
import io.testforge.contract.json.ContractViolation;
import io.testforge.contract.json.JsonContractValidator;
import io.testforge.contract.json.SchemaContract;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The dialect pre-pass decides whether the drift report is worth reading. Get
 * {@code nullable} wrong and every optional field in every response is a
 * violation.
 */
class OpenApiSchemaAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OpenApiSchemaAdapter adapter = new OpenApiSchemaAdapter(MAPPER);
    private final JsonContractValidator validator =
            new JsonContractValidator(ContractMappers.strict(), new ContractProperties(false, 100));

    @Test
    void nullableFieldsAcceptNull() {
        List<ContractViolation> violations = validate("3.0.3", """
                {"type":"object","properties":{"region":{"type":"string","nullable":true}}}""",
                """
                {"region":null}""");

        assertThat(violations).isEmpty();
    }

    @Test
    void nullableStillRejectsTheWrongType() {
        List<ContractViolation> violations = validate("3.0.3", """
                {"type":"object","properties":{"region":{"type":"string","nullable":true}}}""",
                """
                {"region":42}""");

        assertThat(violations).isNotEmpty();
        assertThat(violations.getFirst().path()).contains("region");
    }

    @Test
    void nullIsStillRejectedWithoutNullable() {
        List<ContractViolation> violations = validate("3.0.3", """
                {"type":"object","properties":{"region":{"type":"string"}}}""",
                """
                {"region":null}""");

        assertThat(violations).isNotEmpty();
    }

    @Test
    void referencesResolveThroughTheCarriedComponents() {
        OpenApiDocument document = document("3.0.3", """
                {"openapi":"3.0.3","components":{"schemas":{
                  "Order":{"type":"object","required":["id"],"properties":{"id":{"type":"string"}}}}}}""");

        SchemaContract contract = adapter.toContract(document, schema("""
                {"$ref":"#/components/schemas/Order"}"""), "orders");

        assertThat(validator.validate("{\"id\":\"ord-1\"}", contract)).isEmpty();
        assertThat(validator.validate("{}", contract)).isNotEmpty();
    }

    @Test
    void nullableIsAppliedInsideReferencedComponents() {
        OpenApiDocument document = document("3.0.3", """
                {"openapi":"3.0.3","components":{"schemas":{
                  "Order":{"type":"object","properties":{"note":{"type":"string","nullable":true}}}}}}""");

        SchemaContract contract = adapter.toContract(document, schema("""
                {"$ref":"#/components/schemas/Order"}"""), "orders");

        assertThat(validator.validate("{\"note\":null}", contract)).isEmpty();
    }

    @Test
    void aPropertyLiterallyNamedNullableIsNotRewritten() {
        List<ContractViolation> violations = validate("3.0.3", """
                {"type":"object","properties":{"nullable":{"type":"boolean"}}}""",
                """
                {"nullable":true}""");

        assertThat(violations).isEmpty();
    }

    @Test
    void booleanExclusiveBoundsBecomeNumeric() {
        assertThat(validate("3.0.3", """
                {"type":"object","properties":{"amount":{"type":"integer","minimum":10,"exclusiveMinimum":true}}}""",
                """
                {"amount":10}"""))
                .isNotEmpty();

        assertThat(validate("3.0.3", """
                {"type":"object","properties":{"amount":{"type":"integer","minimum":10,"exclusiveMinimum":true}}}""",
                """
                {"amount":11}"""))
                .isEmpty();
    }

    @Test
    void openapi31KeepsItsOwnDialect() {
        OpenApiDocument document = document("3.1.0", """
                {"openapi":"3.1.0"}""");

        SchemaContract contract = adapter.toContract(document, schema("""
                {"type":"object","properties":{"region":{"type":["string","null"]}}}"""), "health");

        assertThat(contract.schemaJson()).contains("2020-12");
        assertThat(validator.validate("{\"region\":null}", contract)).isEmpty();
    }

    @Test
    void openApiOnlyAnnotationsAreDropped() {
        OpenApiDocument document = document("3.0.3", "{\"openapi\":\"3.0.3\"}");

        SchemaContract contract = adapter.toContract(document, schema("""
                {"type":"object","discriminator":{"propertyName":"kind"},"xml":{"name":"order"}}"""), "orders");

        assertThat(contract.schemaJson()).doesNotContain("discriminator", "xml");
    }

    private List<ContractViolation> validate(String openapi, String schemaJson, String payload) {
        SchemaContract contract = adapter.toContract(
                document(openapi, "{\"openapi\":\"" + openapi + "\"}"), schema(schemaJson), "test");
        return validator.validate(payload, contract);
    }

    private OpenApiDocument document(String openapi, String json) {
        return new OpenApiDocument(openapi, "test", "1", "inline", schema(json));
    }

    private JsonNode schema(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
