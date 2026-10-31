package io.testforge.api.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.testforge.api.discovery.ApiSpecSource;
import io.testforge.api.discovery.OpenApiSpecParser;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a review of the module found, each pinned so it stays fixed. */
class CodegenReviewTest {

    @TempDir
    Path temp;

    @Test
    void aDanglingRefIsReportedAndMappedToObjectInsteadOfCrashingTheGenerator() throws IOException {
        GeneratedApiSources generated = generate("dangling", """
                openapi: 3.0.3
                info: {title: Dangling, version: 1.0.0}
                paths:
                  /a:
                    post:
                      operationId: send
                      requestBody:
                        content:
                          application/json:
                            schema: {$ref: '#/components/schemas/Missing'}
                      responses:
                        '200': {description: OK}
                components:
                  schemas:
                    Present:
                      type: object
                      properties:
                        gone: {$ref: '#/components/schemas/Gone'}
                """);

        assertThat(generated.warnings())
                .contains("Mapped unresolved $ref #/components/schemas/Gone to Object")
                .contains("Mapped unresolved $ref #/components/schemas/Missing to Object");
        assertThat(source(generated, "/model/Present.java")).contains("Object gone");
        assertThat(source(generated, "/client/DefaultApiClient.java")).contains("Response send(Object request)");
        assertThat(compile(generated, "dangling").compiled()).isTrue();
    }

    @Test
    void namesAreBuiltWholeSoKeywordsAndStatusCodesReadNaturally() throws IOException {
        GeneratedApiSources generated = generate("names", """
                openapi: 3.0.3
                info: {title: Names, version: 1.0.0}
                paths:
                  /tasks:
                    get:
                      operationId: listTasks
                      tags: [class]
                      responses:
                        '200':
                          description: OK
                          content:
                            application/json:
                              schema:
                                type: object
                                properties:
                                  total: {type: integer}
                        default:
                          description: Problem
                          content:
                            application/problem+json:
                              schema:
                                type: object
                                properties:
                                  title: {type: string}
                """);

        assertThat(generated.sources()).extracting(GeneratedSource::relativePath)
                .anyMatch(path -> path.endsWith("/client/ClassApiClient.java"))
                .anyMatch(path -> path.endsWith("/model/ListTasks200Response.java"))
                .anyMatch(path -> path.endsWith("/model/ListTasksDefaultResponse.java"));
        assertThat(compile(generated, "names").compiled()).isTrue();
    }

    @Test
    void theCompileClasspathComesFromWhereTheImportedClassesWereLoaded() throws IOException {
        Path jar = Files.createFile(temp.resolve("lib.jar"));
        URL file = jar.toUri().toURL();

        GeneratedSourceCompiler.Classpath resolved = GeneratedSourceCompiler.resolve("", name -> file);

        assertThat(resolved.skipped()).isNull();
        assertThat(resolved.value().split(File.pathSeparator)).containsExactly(jar.toString());
    }

    @Test
    void aFatJarSkipsTheCheckWithTheReasonInsteadOfFailingEverySpec() throws IOException {
        URL nested = URI.create("jar:file:/app/app.jar!/BOOT-INF/lib/module-http.jar!/").toURL();

        GeneratedSourceCompiler.Classpath resolved = GeneratedSourceCompiler.resolve("/app/app.jar", name -> nested);

        assertThat(resolved.value()).isNull();
        assertThat(resolved.skipped())
                .contains("io.testforge.http.ApiClient was loaded from jar:file:/app/app.jar")
                .contains("forge.api-codegen.compile=false");
        assertThat(GeneratedSourceCompiler.resolve("", name -> null).skipped())
                .isEqualTo("io.testforge.http.ApiClient is not on the classpath");
    }

    @Test
    void theDefaultCompilerFindsWhatItNeedsWithoutRelyingOnJavaClassPath() throws IOException {
        String original = System.getProperty("java.class.path");
        GeneratedApiSources generated = generate("located", """
                openapi: 3.0.3
                info: {title: Located, version: 1.0.0}
                paths:
                  /tasks:
                    post:
                      operationId: createTask
                      requestBody:
                        content:
                          application/json:
                            schema:
                              type: object
                              properties:
                                first-name: {type: string}
                      responses:
                        '200': {description: OK}
                """);
        try {
            System.setProperty("java.class.path", "");
            assertThat(compile(generated, "located").compiled()).isTrue();
        } finally {
            System.setProperty("java.class.path", original);
        }
    }

    private GeneratedApiSources generate(String id, String document) throws IOException {
        Path spec = temp.resolve(id + ".yaml");
        Files.writeString(spec, document);
        OpenAPI openApi = new OpenApiSpecParser().parse(new ApiSpecSource(id, spec.toString()));
        return new OpenApiJavaCodeGenerator().generate(id, openApi, "io.testforge.generated");
    }

    private GeneratedSourceCompiler.Result compile(GeneratedApiSources generated, String id) throws IOException {
        Path root = temp.resolve(id + "-src");
        List<Path> files = new ArrayList<>();
        for (GeneratedSource source : generated.sources()) {
            Path file = root.resolve(source.relativePath());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.content());
            files.add(file);
        }
        GeneratedSourceCompiler.Result result =
                new GeneratedSourceCompiler().compile(root, files, temp.resolve(id + "-classes"));
        assertThat(result.errors()).isEmpty();
        return result;
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
