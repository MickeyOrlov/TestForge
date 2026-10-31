package io.testforge.api.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.api.discovery.ApiDiscoveryProperties;
import io.testforge.api.discovery.OpenApiSpecParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApiCodegenRunnerTest {

    @TempDir
    Path temp;

    @Test
    void writesReportAndReplacesOnlyOwnedSpecDirectory() throws IOException {
        Path spec = temp.resolve("openapi.yaml");
        Files.writeString(spec, simpleSpec());
        Path output = temp.resolve("generated");
        ApiCodegenRunner runner = runner(output, Map.of(
                "demo", new ApiDiscoveryProperties.Spec(spec.toString())));

        ApiCodegenReport first = runner.assertGenerated();

        assertThat(first.healthy()).isTrue();
        assertThat(first.specs()).singleElement().satisfies(report -> {
            assertThat(report.models()).isEqualTo(1);
            assertThat(report.clients()).isEqualTo(1);
            assertThat(report.operations()).isEqualTo(1);
            assertThat(report.files()).hasSize(2).allSatisfy(path -> assertThat(Path.of(path)).exists());
        });
        assertThat(Path.of(first.reportJson())).exists();
        assertThat(Files.readString(Path.of(first.reportMarkdown())))
                .contains("API Code Generation Report")
                .contains("models: 1")
                .contains("clients: 1");

        Path stale = output.resolve("demo/stale.java");
        Files.writeString(stale, "stale");
        Path unrelated = output.resolve("keep.txt");
        Files.writeString(unrelated, "keep");

        runner.assertGenerated();

        assertThat(stale).doesNotExist();
        assertThat(unrelated).exists();

        Files.writeString(spec, "openapi: [");
        ApiCodegenReport failed = runner.run();

        assertThat(failed.healthy()).isFalse();
        assertThat(failed.specs()).singleElement().satisfies(report ->
                assertThat(report.error()).contains("Failed to parse OpenAPI spec"));
        assertThat(output.resolve("demo/src/main/java")).doesNotExist();
        assertThat(unrelated).exists();
    }

    @Test
    void enabledCodegenWithoutDiscoverySpecsFailsClearly() {
        ApiCodegenRunner runner = runner(temp.resolve("generated"), Map.of());

        assertThatThrownBy(runner::assertGenerated)
                .isInstanceOf(ApiCodegenException.class)
                .hasMessageContaining("No OpenAPI specs configured under forge.api-discovery.specs");
    }

    @Test
    void hostileSpecIdCannotEscapeOutputDirectory() throws IOException {
        Path spec = temp.resolve("openapi.yaml");
        Files.writeString(spec, simpleSpec());
        Path output = temp.resolve("generated");
        Path sentinel = temp.resolve("sentinel.txt");
        Files.writeString(sentinel, "keep");

        ApiCodegenReport report = runner(output, Map.of(
                "..", new ApiDiscoveryProperties.Spec(spec.toString())))
                .assertGenerated();

        assertThat(report.specs()).singleElement().satisfies(generated ->
                assertThat(Path.of(generated.sourceRoot())).startsWith(output));
        assertThat(sentinel).hasContent("keep");
    }

    @Test
    void reportsNormalizedSpecIdCollisionsWithoutOverwritingFirstSpec() throws IOException {
        Path spec = temp.resolve("openapi.yaml");
        Files.writeString(spec, simpleSpec());
        Path output = temp.resolve("generated");

        ApiCodegenReport report = runner(output, Map.of(
                "demo-api", new ApiDiscoveryProperties.Spec(spec.toString()),
                "demo.api", new ApiDiscoveryProperties.Spec(spec.toString())))
                .run();

        assertThat(report.healthy()).isFalse();
        assertThat(report.specs()).hasSize(2);
        assertThat(report.specs()).filteredOn(ApiCodegenSpecReport::failed).singleElement().satisfies(failed ->
                assertThat(failed.error())
                        .contains("OpenAPI spec id collision")
                        .contains("demo-api"));
        assertThat(output.resolve("demo-api/src/main/java/io/testforge/generated/demo_api/model/TaskList.java"))
                .exists();
    }

    @Test
    void generatedSourcesThatDoNotCompileFailTheSpecWithTheErrors() throws IOException {
        Path spec = temp.resolve("openapi.yaml");
        Files.writeString(spec, simpleSpec());
        Path output = temp.resolve("generated");
        // an empty classpath: the generated client cannot see ApiClient or REST Assured
        ApiCodegenRunner runner = new ApiCodegenRunner(
                new OpenApiSpecParser(),
                new OpenApiJavaCodeGenerator(),
                new ObjectMapper(),
                new ApiDiscoveryProperties(false, null, null, null, null,
                        Map.of("demo", new ApiDiscoveryProperties.Spec(spec.toString()))),
                new ApiCodegenProperties(true, output.toString(), "io.testforge.generated"),
                new GeneratedSourceCompiler(""));

        ApiCodegenReport report = runner.run();

        assertThat(report.healthy()).isFalse();
        assertThat(report.specs()).singleElement().satisfies(generated -> {
            assertThat(generated.compiled()).isFalse();
            assertThat(generated.error()).contains("do not compile");
            assertThat(generated.compileErrors())
                    .anySatisfy(error -> assertThat(error)
                            .startsWith("io/testforge/generated/demo/client/TasksApiClient.java:")
                            .contains("io.restassured"));
        });
        assertThat(Files.readString(Path.of(report.reportMarkdown())))
                .contains("compiled: no")
                .contains("compile errors:");
        assertThatThrownBy(runner::assertGenerated)
                .isInstanceOf(ApiCodegenException.class)
                .hasMessageContaining("TasksApiClient.java:");
    }

    @Test
    void theCompileCheckCanBeTurnedOff() throws IOException {
        Path spec = temp.resolve("openapi.yaml");
        Files.writeString(spec, simpleSpec());
        Path output = temp.resolve("generated");
        ApiCodegenRunner runner = new ApiCodegenRunner(
                new OpenApiSpecParser(),
                new OpenApiJavaCodeGenerator(),
                new ObjectMapper(),
                new ApiDiscoveryProperties(false, null, null, null, null,
                        Map.of("demo", new ApiDiscoveryProperties.Spec(spec.toString()))),
                new ApiCodegenProperties(true, output.toString(), "io.testforge.generated", false),
                new GeneratedSourceCompiler(""));

        ApiCodegenReport report = runner.assertGenerated();

        assertThat(report.specs()).singleElement().satisfies(generated -> {
            assertThat(generated.compiled()).isNull();
            assertThat(generated.compileErrors()).isEmpty();
        });
        assertThat(output.resolve("demo/classes")).doesNotExist();
    }

    @Test
    void aCompiledSpecSaysSoAndKeepsItsClassesInsideItsOwnDirectory() throws IOException {
        Path spec = temp.resolve("openapi.yaml");
        Files.writeString(spec, simpleSpec());
        Path output = temp.resolve("generated");

        ApiCodegenReport report = runner(output, Map.of("demo", new ApiDiscoveryProperties.Spec(spec.toString())))
                .assertGenerated();

        assertThat(report.specs().getFirst().compiled()).isTrue();
        assertThat(output.resolve("demo/classes/io/testforge/generated/demo/client/TasksApiClient.class")).exists();
        assertThat(Files.readString(Path.of(report.reportMarkdown()))).contains("compiled: yes");
    }

    private ApiCodegenRunner runner(Path output, Map<String, ApiDiscoveryProperties.Spec> specs) {
        return new ApiCodegenRunner(
                new OpenApiSpecParser(),
                new OpenApiJavaCodeGenerator(),
                new ObjectMapper(),
                new ApiDiscoveryProperties(false, null, null, null, null, specs),
                new ApiCodegenProperties(true, output.toString(), "io.testforge.generated"));
    }

    private String simpleSpec() {
        return """
                openapi: 3.0.3
                info:
                  title: Demo API
                  version: 1.0.0
                paths:
                  /tasks:
                    get:
                      operationId: listTasks
                      tags: [tasks]
                      responses:
                        '200':
                          description: OK
                          content:
                            application/json:
                              schema:
                                $ref: '#/components/schemas/TaskList'
                components:
                  schemas:
                    TaskList:
                      type: object
                      required: [items]
                      properties:
                        items:
                          type: array
                          items:
                            type: string
                """;
    }
}
