package io.testforge.contract.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.contract.ContractProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonContractValidatorUnitTest {

    private static final MessageContract ORDER = MessageContract.named("order")
            .required("$.id", FieldType.STRING)
            .required("$.amount", FieldType.NUMBER)
            .required("$.items", FieldType.ARRAY)
            .optional("$.note", FieldType.STRING)
            .nullable("$.coupon", FieldType.STRING)
            .build();

    private static JsonContractValidator validator(boolean failFast, int max) {
        return new JsonContractValidator(new ObjectMapper(), new ContractProperties(failFast, max));
    }

    private final JsonContractValidator validator = validator(false, 0);

    @Test
    void aConformingPayloadHasNoViolations() {
        assertThat(validator.validate("{\"id\":\"o-1\",\"amount\":9.5,\"items\":[],\"coupon\":null}", ORDER))
                .isEmpty();
    }

    @Test
    void reportsEveryProblemInOnePass() {
        List<ContractViolation> violations =
                validator.validate("{\"id\":7,\"items\":{},\"note\":null,\"coupon\":null}", ORDER);

        assertThat(violations).extracting(ContractViolation::path, ContractViolation::code).containsExactly(
                org.assertj.core.groups.Tuple.tuple("$.id", "type"),
                org.assertj.core.groups.Tuple.tuple("$.amount", "missing"),
                org.assertj.core.groups.Tuple.tuple("$.items", "type"),
                org.assertj.core.groups.Tuple.tuple("$.note", "null"));
        assertThat(violations.getFirst().message()).isEqualTo("Expected STRING but got INTEGER");
        assertThat(violations).allMatch(v -> v.contract().equals("order"));
    }

    @Test
    void optionalFieldsMayBeAbsentButNotNull() {
        assertThat(validator.validate("{\"id\":\"o\",\"amount\":1,\"items\":[]}", ORDER)).isEmpty();
        assertThat(validator.validate("{\"id\":\"o\",\"amount\":1,\"items\":[],\"note\":null}", ORDER))
                .extracting(ContractViolation::code).containsExactly("null");
    }

    @Test
    void integerIsStricterThanNumber() {
        MessageContract counter = MessageContract.named("c").required("$.n", FieldType.INTEGER).build();

        assertThat(validator.validate("{\"n\":3}", counter)).isEmpty();
        assertThat(validator.validate("{\"n\":3.5}", counter)).extracting(ContractViolation::message)
                .containsExactly("Expected INTEGER but got NUMBER");
    }

    @Test
    void failFastStopsAtTheFirstViolation() {
        assertThat(validator(true, 0).validate("{}", ORDER)).hasSize(1);
    }

    @Test
    void maxViolationsCapsTheReport() {
        assertThat(validator(false, 2).validate("{}", ORDER)).hasSize(2);
    }

    @Test
    void invalidJsonIsAViolationNotAnException() {
        assertThat(validator.validate("{oops", ORDER))
                .singleElement()
                .satisfies(v -> {
                    assertThat(v.code()).isEqualTo("invalid-json");
                    assertThat(v.path()).isEqualTo("$");
                });
    }

    @Test
    void assertValidCarriesTheViolations() {
        assertThatThrownBy(() -> validator.assertValid("{}", ORDER))
                .isInstanceOfSatisfying(ContractValidationException.class,
                        e -> assertThat(e.violations()).hasSize(3));
    }

    @Test
    void contractsRejectBlankNamesAndPathsAndDefaultTheType() {
        assertThatThrownBy(() -> MessageContract.named(" ").build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FieldRule("", FieldType.STRING, true, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new FieldRule("$.x", null, true, false).type()).isEqualTo(FieldType.ANY);
    }

    @Test
    void contractRulesAreImmutable() {
        assertThatThrownBy(() -> ORDER.rules().add(new FieldRule("$.x", FieldType.ANY, false, false)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
