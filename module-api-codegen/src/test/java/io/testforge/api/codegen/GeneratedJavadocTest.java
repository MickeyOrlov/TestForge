package io.testforge.api.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.testforge.api.discovery.ApiSpecSource;
import io.testforge.api.discovery.OpenApiSpecParser;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneratedJavadocTest {

    @TempDir
    Path temp;

    @Test
    void theDocumentsOwnWordsBecomeJavadoc() throws IOException {
        GeneratedApiSources generated = generate("docs", """
                openapi: 3.0.3
                info: {title: Docs, version: 1.0.0}
                tags:
                  - name: tasks
                    description: Work items of a team.
                paths:
                  /tasks/{id}:
                    get:
                      operationId: getTask
                      tags: [tasks]
                      summary: One task by id.
                      description: |
                        Archived tasks are returned too.
                        Use the status to tell them apart.
                      parameters:
                        - name: id
                          in: path
                          required: true
                          description: The task's id.
                          schema: {type: string}
                      responses:
                        '200':
                          description: OK
                          content:
                            application/json:
                              schema: {$ref: '#/components/schemas/Task'}
                        '404':
                          description: Not found
                components:
                  schemas:
                    Task:
                      type: object
                      description: A unit of work.
                      properties:
                        title:
                          type: string
                          description: Shown in lists, at most 80 characters.
                """);

        assertThat(source(generated, "/client/TasksApiClient.java"))
                .contains(" * Work items of a team.\n")
                .contains("     * One task by id.\n")
                .contains("     * Archived tasks are returned too.\n     * Use the status to tell them apart.\n")
                .contains("     * <code>GET /tasks/&#123;id&#125;</code> — responds <code>200 Task</code>, "
                        + "<code>404</code>\n")
                .contains("     * @param id The task's id.\n");
        assertThat(source(generated, "/model/Task.java"))
                .contains(" * A unit of work.\n")
                .contains(" * @param title Shown in lists, at most 80 characters.\n")
                .contains("Generated from OpenAPI. Regenerate instead of editing this file.");
        assertThat(compile(generated)).isTrue();
    }

    @Test
    void documentTextCannotEndTheCommentAndBecomeCode() throws Exception {
        GeneratedApiSources generated = generate("hostile", """
                openapi: 3.0.3
                info: {title: Hostile, version: 1.0.0}
                tags:
                  - name: tasks
                    description: "*/ public static final int TAG_INJECTED = 1; /*"
                paths:
                  /tasks:
                    get:
                      operationId: listTasks
                      tags: [tasks]
                      summary: "\\\\u002a/ public static void injected() {} /\\\\u002a"
                      description: "@deprecated <b>not</b> {@link Object}"
                      parameters:
                        - name: q
                          in: query
                          description: "*/ int PARAM_INJECTED; /*"
                          schema: {type: string}
                      responses:
                        '200':
                          description: OK
                          content:
                            application/json:
                              schema: {$ref: '#/components/schemas/Task'}
                components:
                  schemas:
                    Task:
                      type: object
                      description: "*/ static { System.exit(1); } /*"
                      properties:
                        title:
                          type: string
                          description: "*/ String injected, /*"
                """);

        assertThat(source(generated, "/client/TasksApiClient.java"))
                .contains("*&#47; public static final int TAG_INJECTED = 1; &#47;*")
                .contains("&#92;u002a/ public static void injected()")
                .contains("&#64;deprecated &lt;b&gt;not&lt;/b&gt; &#123;&#64;link Object&#125;")
                .doesNotContain("@deprecated");
        assertThat(compile(generated)).isTrue();

        try (URLClassLoader loader = new URLClassLoader(new URL[] {temp.resolve("classes").toUri().toURL()},
                getClass().getClassLoader())) {
            Class<?> client = loader.loadClass("io.testforge.generated.hostile.client.TasksApiClient");
            assertThat(Arrays.stream(client.getDeclaredFields()).map(Field::getName)).containsExactly("api");
            assertThat(Arrays.stream(client.getDeclaredMethods()).map(Method::getName))
                    .containsExactly("listTasks");
            Class<?> task = loader.loadClass("io.testforge.generated.hostile.model.Task");
            assertThat(task.getRecordComponents()).hasSize(1);
        }
    }

    private GeneratedApiSources generate(String id, String document) throws IOException {
        Path spec = temp.resolve(id + ".yaml");
        Files.writeString(spec, document);
        OpenAPI openApi = new OpenApiSpecParser().parse(new ApiSpecSource(id, spec.toString()));
        return new OpenApiJavaCodeGenerator().generate(id, openApi, "io.testforge.generated");
    }

    private Boolean compile(GeneratedApiSources generated) throws IOException {
        Path root = temp.resolve("src");
        List<Path> files = new ArrayList<>();
        for (GeneratedSource source : generated.sources()) {
            Path file = root.resolve(source.relativePath());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.content(), StandardCharsets.UTF_8);
            files.add(file);
        }
        GeneratedSourceCompiler.Result result =
                new GeneratedSourceCompiler().compile(root, files, temp.resolve("classes"));
        assertThat(result.errors()).isEmpty();
        return result.compiled();
    }

    private String source(GeneratedApiSources generated, String suffix) {
        return generated.sources().stream()
                .filter(source -> source.relativePath().endsWith(suffix))
                .findFirst()
                .orElseThrow()
                .content();
    }
}
