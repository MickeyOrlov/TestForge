package io.testforge.contract.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.contract.ContractProperties;
import org.junit.jupiter.api.Test;

class SchemaContractUnitTest {

    private static final SchemaContract EVENT = SchemaContract.of("event", """
            {
              "type": "object",
              "required": ["id", "kind"],
              "properties": {
                "id":   {"type": "string", "pattern": "^ev-"},
                "kind": {"enum": ["created", "deleted"]},
                "size": {"type": "integer", "minimum": 0}
              }
            }
            """);

    private final JsonContractValidator validator =
            new JsonContractValidator(new ObjectMapper(), new ContractProperties(false, 0));

    @Test
    void conformingPayloadPasses() {
        assertThat(validator.validate("{\"id\":\"ev-1\",\"kind\":\"created\",\"size\":3}", EVENT)).isEmpty();
    }

    @Test
    void requiredViolationPointsAtTheMissingProperty() {
        assertThat(validator.validate("{\"id\":\"ev-1\"}", EVENT))
                .singleElement()
                .satisfies(v -> {
                    assertThat(v.code()).isEqualTo("required");
                    assertThat(v.path()).isEqualTo("$.kind");
                });
    }

    @Test
    void violationsAreOrderedByKeywordPriorityThenPath() {
        var violations = validator.validate("{\"id\":\"x\",\"kind\":\"other\",\"size\":-1}", EVENT);

        assertThat(violations).extracting(ContractViolation::code).containsExactly("enum", "pattern", "minimum");
    }

    @Test
    void failFastKeepsOnlyTheMostImportantViolation() {
        var failFast = new JsonContractValidator(new ObjectMapper(), new ContractProperties(true, 0));

        assertThat(failFast.validate("{\"kind\":\"other\"}", EVENT))
                .extracting(ContractViolation::code).containsExactly("required");
    }

    @Test
    void invalidPayloadJsonIsAViolation() {
        assertThat(validator.validate("not json", EVENT))
                .extracting(ContractViolation::code).containsExactly("invalid-json");
    }

    @Test
    void anInvalidSchemaIsReportedAsSuch() {
        SchemaContract broken = SchemaContract.of("broken", "{not a schema");

        assertThatThrownBy(() -> validator.validate("{}", broken))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a valid JSON Schema");
    }

    @Test
    void missingSchemaResourceNamesTheLocation() {
        assertThatThrownBy(() -> SchemaContract.fromResource("x", "contracts/does-not-exist.json"))
                .hasMessageContaining("contracts/does-not-exist.json");
    }
}
