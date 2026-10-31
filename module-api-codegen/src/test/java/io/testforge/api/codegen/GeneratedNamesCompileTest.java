package io.testforge.api.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.testforge.api.discovery.ApiSpecSource;
import io.testforge.api.discovery.OpenApiSpecParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Names a real document can carry that Java will not take as they are: an
 * accessor clashing with {@code Object}, a parameter shadowing the generated
 * client's own locals, a schema named like a type the generated code imports.
 */
class GeneratedNamesCompileTest {

    @TempDir
    Path temp;

    @Test
    void namesThatClashWithJavaOrTheGeneratedCodeStillCompile() throws IOException {
        GeneratedApiSources generated = generate("""
                openapi: 3.0.3
                info: {title: Names, version: 1.0.0}
                paths:
                  /things/{api}:
                    post:
                      operationId: toString
                      tags: [things]
                      parameters:
                        - {name: api, in: path, required: true, schema: {type: string}}
                        - {name: requestSpec, in: query, schema: {type: string}}
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema: {$ref: '#/components/schemas/Response'}
                      responses:
                        '200':
                          description: OK
                  /errors:
                    put:
                      operationId: hashCode
                      tags: [things]
                      requestBody:
                        content:
                          application/json:
                            schema: {$ref: '#/components/schemas/Error'}
                      responses:
                        '204':
                          description: none
                components:
                  schemas:
                    Response:
                      type: object
                      required: [hashCode]
                      properties:
                        hashCode: {type: string}
                        toString: {type: integer}
                        getClass: {type: string}
                        wait: {type: boolean}
                        label: {$ref: '#/components/schemas/String'}
                    String:
                      type: object
                      properties:
                        text: {type: string}
                    Error:
                      type: object
                      properties:
                        message: {type: string}
                """);

        GeneratedSourceCompiler.Result result = compile(generated);

        assertThat(result.errors()).isEmpty();
        assertThat(result.compiled()).isTrue();

        String response = source(generated, "/model/ResponseModel.java");
        assertThat(response)
                .contains("@JsonProperty(\"hashCode\") String hashCodeValue")
                .contains("@JsonProperty(\"toString\") Integer toStringValue")
                .contains("@JsonProperty(\"getClass\") String getClassValue")
                .contains("@JsonProperty(\"wait\") Boolean waitValue")
                .contains("StringModel label");
        String client = source(generated, "/client/ThingsApiClient.java");
        assertThat(client)
                .contains("public Response toStringCall(")
                .contains("String api2,")
                .contains("String requestSpec2,")
                .contains("ResponseModel request)")
                .contains("requestSpec.pathParam(\"api\", api2)")
                .contains("requestSpec.queryParam(\"requestSpec\", requestSpec2)")
                .contains("public Response hashCodeCall(Error request)")
                .contains("import io.testforge.generated.names.model.Error;");
    }

    @Test
    void aCompileErrorNamesTheFileAndLine() throws IOException {
        Path root = temp.resolve("broken");
        Path file = root.resolve("demo/Broken.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package demo;\n\npublic record Broken(String hashCode) {\n}\n");

        GeneratedSourceCompiler.Result result = new GeneratedSourceCompiler()
                .compile(root, List.of(file), temp.resolve("broken-classes"));

        assertThat(result.compiled()).isFalse();
        assertThat(result.errors()).singleElement().asString().startsWith("demo/Broken.java:3: ");
    }

    private GeneratedApiSources generate(String document) throws IOException {
        Path spec = temp.resolve("names.yaml");
        Files.writeString(spec, document);
        OpenAPI openApi = new OpenApiSpecParser().parse(new ApiSpecSource("names", spec.toString()));
        return new OpenApiJavaCodeGenerator().generate("names", openApi, "io.testforge.generated");
    }

    private GeneratedSourceCompiler.Result compile(GeneratedApiSources generated) throws IOException {
        Path root = temp.resolve("src");
        List<Path> files = new ArrayList<>();
        for (GeneratedSource source : generated.sources()) {
            Path file = root.resolve(source.relativePath());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.content(), StandardCharsets.UTF_8);
            files.add(file);
        }
        return new GeneratedSourceCompiler().compile(root, files, temp.resolve("classes"));
    }

    private String source(GeneratedApiSources generated, String suffix) {
        return generated.sources().stream()
                .filter(source -> source.relativePath().endsWith(suffix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + suffix + " in "
                        + generated.sources().stream().map(GeneratedSource::relativePath).toList()))
                .content();
    }
}
