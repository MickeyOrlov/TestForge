package io.testforge.contract.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ContractChangesTest {

    private static final SchemaContract V1 = SchemaContract.of("partner-event", """
            {"type":"object","required":["eventId","status","amount"],
             "properties":{
               "eventId":{"type":"string"},
               "status":{"type":"string","enum":["accepted","rejected"]},
               "amount":{"type":"number"},
               "note":{"type":"string"},
               "legacyCode":{"type":"string"},
               "items":{"type":"array","items":{"type":"object","required":["sku"],
                        "properties":{"sku":{"type":"string"}}}}}}""");

    private static final SchemaContract V2 = SchemaContract.of("partner-event", """
            {"type":"object","required":["eventId","status","channel"],
             "properties":{
               "eventId":{"type":"string"},
               "status":{"type":"string","enum":["accepted","rejected","pending"]},
               "amount":{"type":["number","null"]},
               "note":{"type":"string"},
               "channel":{"type":"string"},
               "items":{"type":"array","items":{"type":"object","required":[],
                        "properties":{"sku":{"type":"string"}}}}}}""");

    @Test
    void eachChangeIsJudgedFromTheConsumersSideWorstFirst() {
        List<ContractChange> changes = ContractChanges.compare(V1, V2);

        assertThat(changes).extracting(ContractChange::toString).containsExactly(
                "BREAKING $.amount: no longer required: may be missing",
                "BREAKING $.amount: may now arrive as [null] (was [number])",
                "BREAKING $.items[].sku: no longer required: may be missing",
                "RISKY $.legacyCode: optional field removed: consumers reading it get nothing",
                "RISKY $.status: new values [pending]: a consumer switching over the old ones may not handle them",
                "SAFE $.channel: new field (required)");
    }

    @Test
    void theSameSchemaHasNoChanges() {
        assertThat(ContractChanges.compare(V1, V1)).isEmpty();
    }

    @Test
    void narrowingIsSafeAndARemovedRequiredFieldBreaks() {
        SchemaContract before = SchemaContract.of("e", """
                {"type":"object","required":["id"],"properties":{"id":{"type":["string","null"]},
                 "kind":{"type":"string","enum":["a","b"]}}}""");
        SchemaContract after = SchemaContract.of("e", """
                {"type":"object","required":[],"properties":{"kind":{"type":"string","enum":["a"]}}}""");

        assertThat(ContractChanges.compare(before, after)).extracting(ContractChange::toString).containsExactly(
                "BREAKING $.id: required field removed",
                "SAFE $.kind: values no longer sent [b]");
        assertThat(ContractChanges.compare(after, SchemaContract.of("e", """
                {"type":"object","properties":{"kind":{"type":"string","enum":["a"]},"id":{"type":"string"}}}""")))
                .extracting(ContractChange::impact).containsExactly(ContractChange.Impact.SAFE);
    }

    @Test
    void aNewFieldIsRiskyForAStrictOldSchemaAndCompositionIsNotGuessed() {
        SchemaContract strict = SchemaContract.of("e", """
                {"type":"object","additionalProperties":false,"properties":{"id":{"type":"string"},
                 "body":{"oneOf":[{"type":"string"}]}}}""");
        SchemaContract changed = SchemaContract.of("e", """
                {"type":"object","properties":{"id":{"type":"string"},"extra":{"type":"string"},
                 "body":{"oneOf":[{"type":"integer"}]}}}""");

        assertThat(ContractChanges.compare(strict, changed)).extracting(ContractChange::toString).containsExactly(
                "RISKY $.extra: new field where the old schema allowed no additional properties",
                "UNKNOWN $.body: oneOf changed; not compared, review by hand");
    }

    @Test
    void assertNoBreakingFailsWithEverythingButTheSafeChanges() {
        assertThatThrownBy(() -> ContractChanges.assertNoBreaking(ContractChanges.compare(V1, V2)))
                .isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("3 breaking contract changes:")
                .hasMessageContaining("RISKY $.status")
                .hasMessageNotContaining("SAFE");
    }
}
