package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import io.testforge.contract.json.ContractViolation;
import io.testforge.contract.json.FieldType;
import io.testforge.contract.json.JsonContractValidator;
import io.testforge.contract.json.MessageContract;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Demonstrates module-contract: describe the message shape once and validate
 * every payload pulled from an API, queue, file, or replayed fixture against it.
 */
@SpringBootTest
class JsonContractValidatorTest {

    @Autowired
    JsonContractValidator contracts;

    @Test
    void validatesPayloadAgainstMessageTemplate() {
        String payload = """
                {
                  "eventId": "evt-1",
                  "payload": {
                    "status": "accepted",
                    "details": null,
                    "items": [
                      { "sku": "SKU-1" }
                    ]
                  }
                }
                """;

        assertThat(contracts.validate(payload, partnerStatusContract())).isEmpty();
    }

    @Test
    void reportsMessageShapeDriftInOnePass() {
        String payload = """
                {
                  "eventId": 42,
                  "payload": {
                    "details": "not-an-object",
                    "items": []
                  }
                }
                """;

        assertThat(contracts.validate(payload, partnerStatusContract()))
                .extracting(ContractViolation::path, ContractViolation::code)
                .containsExactly(
                        tuple("$.eventId", "type"),
                        tuple("$.payload.status", "missing"),
                        tuple("$.payload.details", "type"),
                        tuple("$.payload.items[0].sku", "missing"));
    }

    private MessageContract partnerStatusContract() {
        return MessageContract.named("partner-status")
                .required("$.eventId", FieldType.STRING)
                .required("$.payload.status", FieldType.STRING)
                .nullable("$.payload.details", FieldType.OBJECT)
                .required("$.payload.items[0].sku", FieldType.STRING)
                .build();
    }
}
