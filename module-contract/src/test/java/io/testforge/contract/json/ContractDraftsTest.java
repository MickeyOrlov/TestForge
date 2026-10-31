package io.testforge.contract.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.contract.ContractProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContractDraftsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonContractValidator validator = new JsonContractValidator(mapper, new ContractProperties(false, 100));

    private static final List<String> SAMPLES = List.of(
            """
            {"eventId":"e-1","amount":10,"customer":{"email":"ann@example.test","vip":true},
             "items":[{"sku":"A","qty":1}],"reason":null}""",
            """
            {"eventId":"e-2","amount":10.5,"customer":{"email":"bob@example.test"},
             "items":[{"sku":"B","qty":2,"note":"gift"}],"reason":"late"}""");

    private JsonNode draft() throws Exception {
        return mapper.readTree(ContractDrafts.fromSamples("partner-event", SAMPLES).schemaJson());
    }

    @Test
    void fieldsInEverySampleAreRequiredAndTypesAreTheUnionSeen() throws Exception {
        JsonNode schema = draft();

        assertThat(schema.path("title").asText()).isEqualTo("partner-event");
        assertThat(schema.path("required")).extracting(JsonNode::asText)
                .containsExactly("amount", "customer", "eventId", "items", "reason");
        assertThat(schema.at("/properties/amount/type").asText()).isEqualTo("number");
        assertThat(schema.at("/properties/reason/type")).extracting(JsonNode::asText)
                .containsExactly("null", "string");
        assertThat(schema.at("/properties/customer/required")).extracting(JsonNode::asText).containsExactly("email");
        assertThat(schema.at("/properties/items/items/required")).extracting(JsonNode::asText)
                .containsExactly("qty", "sku");
        assertThat(schema.at("/properties/items/items/properties/note/type").asText()).isEqualTo("string");
    }

    @Test
    void theDraftAcceptsItsSamplesAndCatchesDrift() {
        SchemaContract draft = ContractDrafts.fromSamples("partner-event", SAMPLES);

        SAMPLES.forEach(sample -> assertThat(validator.validate(sample, draft)).isEmpty());
        assertThat(validator.validate("""
                {"eventId":"e-3","amount":"10","customer":{},"items":[],"reason":null}""", draft))
                .extracting(ContractViolation::code).contains("required", "type");
    }

    @Test
    void noSampleValueEndsUpInTheDraft() {
        String schema = ContractDrafts.fromSamples("partner-event", SAMPLES).schemaJson();

        assertThat(schema).doesNotContain("ann@example.test", "e-1", "gift", "late", "\"A\"");
    }

    @Test
    void aSampleThatIsNotJsonIsNamed() {
        assertThatThrownBy(() -> ContractDrafts.fromSamples("partner-event", List.of("{}", "{oops")))
                .hasMessageContaining("Sample 2 of partner-event is not JSON");
        assertThatThrownBy(() -> ContractDrafts.fromSamples("partner-event", List.of()))
                .hasMessageContaining("at least one sample");
    }
}
