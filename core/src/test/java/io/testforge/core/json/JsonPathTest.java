package io.testforge.core.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JsonPathTest {

    private static JsonNode json(String text) throws Exception {
        return new ObjectMapper().readTree(text);
    }

    @Test
    void readsObjectPathsInEverySupportedSyntax() throws Exception {
        JsonNode root = json("{\"a\":{\"b\":7}}");

        assertThat(JsonPath.read(root, "$.a.b").asInt()).isEqualTo(7);
        assertThat(JsonPath.read(root, "a.b").asInt()).isEqualTo(7);
        assertThat(JsonPath.read(root, ".a.b").asInt()).isEqualTo(7);
        assertThat(JsonPath.read(root, "/a/b").asInt()).isEqualTo(7);
        assertThat(JsonPath.read(root, "$")).isEqualTo(root);
    }

    @Test
    void readsArrayIndicesInsideAndAtTheRoot() throws Exception {
        assertThat(JsonPath.read(json("{\"items\":[{\"sku\":\"a\"},{\"sku\":\"b\"}]}"), "$.items[1].sku").asText())
                .isEqualTo("b");
        assertThat(JsonPath.read(json("[{\"id\":42},{\"id\":43}]"), "$[0].id").asInt()).isEqualTo(42);
        assertThat(JsonPath.read(json("[[1,2],[3,4]]"), "$[1][0]").asInt()).isEqualTo(3);
    }

    @Test
    void missingPathsAreMissingNotErrors() throws Exception {
        JsonNode root = json("[{\"id\":1}]");

        assertThat(JsonPath.read(root, "$[5].id").isMissingNode()).isTrue();
        assertThat(JsonPath.read(root, "$[x].id").isMissingNode()).isTrue();
        assertThat(JsonPath.read(null, "$.a").isMissingNode()).isTrue();
    }

    @org.junit.jupiter.api.Test
    void aNullPathIsRefusedWithAHint() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> JsonPath.read(
                        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\"$\"");
    }
}
