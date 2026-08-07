package io.testforge.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.api.codegen.ApiCodegenReport;
import io.testforge.api.codegen.ApiCodegenRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        classes = SmokeTestApplication.class,
        properties = {
                "forge.api-codegen.enabled=true",
                "forge.api-codegen.output-dir=build/api-codegen-smoke",
                "forge.api-codegen.base-package=io.testforge.smoke.generated",
                "forge.api-discovery.specs.demo.location=classpath:/openapi/demo-api.yaml"
        })
class PublishedApiCodegenSmokeTest {

    @Autowired
    ApiCodegenRunner codegen;

    @Test
    void generatesSourcesFromPublishedLibrary() throws IOException {
        ApiCodegenReport report = codegen.assertGenerated();

        assertThat(report.healthy()).isTrue();
        assertThat(report.specs()).singleElement().satisfies(spec -> {
            assertThat(spec.specId()).isEqualTo("demo");
            assertThat(spec.models()).isGreaterThanOrEqualTo(2);
            assertThat(spec.clients()).isEqualTo(1);
            assertThat(spec.operations()).isEqualTo(2);
            assertThat(spec.files()).allSatisfy(file -> assertThat(Path.of(file)).exists());
        });

        Path sourceRoot = Path.of(report.specs().getFirst().sourceRoot());
        assertThat(sourceRoot.resolve("io/testforge/smoke/generated/demo/model/Task.java")).exists();
        assertThat(Files.readString(sourceRoot.resolve(
                "io/testforge/smoke/generated/demo/client/DefaultTypeApiClient.java")))
                .contains("Response listTasks()")
                .contains("Response createTask(CreateTaskRequest request)");
    }
}
