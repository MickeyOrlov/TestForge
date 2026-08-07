package io.testforge.api.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenApiReaderTest {

    private final OpenApiReader reader = new OpenApiReader(new ObjectMapper(), null, null);

    @Test
    void readsYamlFromTheClasspath() {
        OpenApiDocument document = reader.read("classpath:openapi/orders.yaml");

        assertThat(document.openapi()).isEqualTo("3.0.3");
        assertThat(document.title()).isEqualTo("Orders API");
        assertThat(document.version()).isEqualTo("1.4.0");
        assertThat(document.oas31()).isFalse();
        assertThat(document.paths().size()).isEqualTo(3);
    }

    @Test
    void readsJsonFromAFile(@TempDir Path directory) throws IOException {
        Path spec = directory.resolve("openapi.json");
        Files.writeString(spec, """
                {"openapi":"3.1.0","info":{"title":"Payments","version":"2"},"paths":{}}""");

        OpenApiDocument document = reader.read("file:" + spec);

        assertThat(document.title()).isEqualTo("Payments");
        assertThat(document.oas31()).isTrue();
    }

    @Test
    void resolvesLocalReferencesAndIgnoresRemoteOnes() {
        OpenApiDocument document = reader.read("classpath:openapi/orders.yaml");

        assertThat(document.resolve("#/components/schemas/Order").path("type").asText()).isEqualTo("object");
        assertThat(document.resolve("./common.yaml#/Order").isMissingNode()).isTrue();
        assertThat(document.resolve(null).isMissingNode()).isTrue();
    }

    @Test
    void swaggerDocumentsFailWithAnActionableMessage(@TempDir Path directory) throws IOException {
        Path spec = directory.resolve("swagger.json");
        Files.writeString(spec, """
                {"swagger":"2.0","info":{"title":"Legacy","version":"1"},"paths":{}}""");

        assertThatThrownBy(() -> reader.read("file:" + spec))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Swagger 2.0")
                .hasMessageContaining("convert it to OpenAPI 3");
    }

    @Test
    void unknownSourcePrefixIsRejected() {
        assertThatThrownBy(() -> reader.read("s3://bucket/openapi.json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("classpath:, file:, path: or http(s)://");
    }
}
