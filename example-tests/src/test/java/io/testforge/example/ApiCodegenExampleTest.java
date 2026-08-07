package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.api.codegen.ApiCodegenReport;
import io.testforge.api.codegen.ApiCodegenRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "forge.api-codegen.enabled=true",
        "forge.api-codegen.output-dir=build/api-codegen/example",
        "forge.api-codegen.base-package=io.testforge.generated",
        "forge.api-discovery.specs.demo.location=classpath:/openapi/demo-api.yaml"
})
class ApiCodegenExampleTest {

    private static final Path OUTPUT_DIR = Path.of("build/api-codegen/example");

    @Autowired
    ApiCodegenRunner codegen;

    @BeforeEach
    void reset() throws IOException {
        deleteIfExists(OUTPUT_DIR);
    }

    @Test
    void generatesRecordsAndClientSkeletonsFromLocalOpenApi() throws IOException {
        ApiCodegenReport report = codegen.assertGenerated();

        assertThat(report.healthy()).isTrue();
        assertThat(report.specs()).singleElement().satisfies(spec -> {
            assertThat(spec.models()).isGreaterThanOrEqualTo(2);
            assertThat(spec.clients()).isEqualTo(1);
            assertThat(spec.operations()).isEqualTo(2);
            assertThat(spec.files()).allSatisfy(file -> assertThat(Path.of(file)).exists());
        });
        assertThat(Files.readString(Path.of(report.reportMarkdown())))
                .contains("API Code Generation Report")
                .contains("demo");

        Path sourceRoot = Path.of(report.specs().getFirst().sourceRoot());
        assertThat(sourceRoot.resolve("io/testforge/generated/demo/model/Task.java")).exists();
        assertThat(sourceRoot.resolve("io/testforge/generated/demo/model/CreateTaskRequest.java")).exists();
        assertThat(Files.readString(sourceRoot.resolve(
                "io/testforge/generated/demo/client/TasksApiClient.java")))
                .contains("Response listTasks()")
                .contains("Response createTask(CreateTaskRequest request)");
    }

    private void deleteIfExists(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(this::delete);
        }
    }

    private void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
