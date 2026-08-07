package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.testforge.api.discovery.ApiDiscoveryReport;
import io.testforge.api.discovery.ApiDiscoveryRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "forge.api-discovery.enabled=true",
        "forge.api-discovery.output-dir=build/api-discovery/example-current",
        "forge.api-discovery.baseline-dir=build/api-discovery/example-baseline",
        "forge.api-discovery.specs.demo.location=classpath:/openapi/demo-api.yaml"
})
class ApiDiscoveryExampleTest {

    private static final Path OUTPUT_DIR = Path.of("build/api-discovery/example-current");
    private static final Path BASELINE_DIR = Path.of("build/api-discovery/example-baseline");

    @Autowired
    ApiDiscoveryRunner discovery;

    @BeforeEach
    void reset() throws IOException {
        deleteIfExists(OUTPUT_DIR);
        deleteIfExists(BASELINE_DIR);
    }

    @Test
    void discoversApiCatalogAndShapeSnapshotsOffline() throws IOException {
        assertThatCode(discovery::assertHealthy).doesNotThrowAnyException();

        ApiDiscoveryReport report = discovery.run();

        assertThat(report.healthy()).isTrue();
        assertThat(report.specs()).hasSize(1);
        assertThat(report.specs().getFirst().endpoints()).isEqualTo(2);
        assertThat(Path.of(report.specs().getFirst().catalogArtifact())).exists();
        assertThat(report.specs().getFirst().shapes())
                .extracting(shape -> shape.shapeDiff().baselinePresent())
                .containsOnly(false);
        assertThat(Files.readString(Path.of(report.reportMarkdown())))
                .contains("API Discovery Report")
                .contains("demo");
    }

    private void deleteIfExists(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(this::delete);
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
