package io.testforge.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonScopeWriterTest {

    private final JsonScopeWriter writer = new JsonScopeWriter(new ObjectMapper());

    @ParameterizedTest
    @ValueSource(strings = {"$.meta.scope", "/meta/scope", ".meta.scope", "meta.scope"})
    void acceptsEveryDocumentedPathSyntax(String path) {
        assertThat(writer.write("{\"a\":1}", path, "s-1"))
                .contains("{\"a\":1,\"meta\":{\"scope\":\"s-1\"}}");
    }

    @Test
    void writesIntoAnExistingObjectAndOverwritesTheTarget() {
        assertThat(writer.write("{\"meta\":{\"scope\":\"old\",\"keep\":true}}", "$.meta.scope", "new"))
                .contains("{\"meta\":{\"scope\":\"new\",\"keep\":true}}");
    }

    @Test
    void replacesANullIntermediateWithAnObject() {
        assertThat(writer.write("{\"meta\":null}", "$.meta.scope", "s"))
                .contains("{\"meta\":{\"scope\":\"s\"}}");
    }

    @Test
    void refusesToClobberANonObjectIntermediate() {
        assertThatThrownBy(() -> writer.write("{\"meta\":\"text\"}", "$.meta.scope", "s"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'meta'")
                .hasMessageContaining("STRING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "[1,2]", "\"just a string\"", "a=1&b=2", "{not json"})
    void leavesBodiesThatAreNotAJsonObjectAlone(String body) {
        assertThat(writer.write(body, "$.scope", "s")).isEmpty();
    }

    @Test
    void nullBodyIsLeftAlone() {
        assertThat(writer.write(null, "$.scope", "s")).isEmpty();
    }

    @Test
    void arrayIndicesAreRejectedWithAnExplanation() {
        assertThatThrownBy(() -> writer.write("{}", "$.items[0].scope", "s"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("array index");
    }

    @ParameterizedTest
    @ValueSource(strings = {"$.", "/", "."})
    void pathsThatNameNoFieldAreRejected(String path) {
        assertThatThrownBy(() -> writer.write("{}", path, "s"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not point at a field");
    }
}
