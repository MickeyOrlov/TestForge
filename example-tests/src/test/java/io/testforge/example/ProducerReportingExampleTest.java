package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.api.discovery.ApiDiscoveryRunner;
import io.testforge.reporting.ArtifactReportingLifecycle;
import io.testforge.reporting.ArtifactRunLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * A runner that writes its reports to its own {@code output-dir} still shows
 * up in the unified run index: {@code manifest.json} lists the report with
 * the producer's verdict and, because the configured directory is relative,
 * where the file actually lives.
 */
@SpringBootTest(properties = {
        "forge.reporting.artifacts.enabled=true",
        "forge.reporting.artifacts.dir=build/producer-reporting-example",
        "forge.reporting.artifacts.run-id=producer-run",
        "forge.api-discovery.enabled=true",
        "forge.api-discovery.output-dir=build/api-discovery/producer-reporting",
        "forge.api-discovery.baseline-dir=build/api-discovery/producer-reporting-baseline",
        "forge.api-discovery.specs.demo.location=classpath:/openapi/demo-api.yaml"
})
class ProducerReportingExampleTest {

    @Autowired
    ApiDiscoveryRunner discovery;

    @Autowired
    ArtifactReportingLifecycle reportingLifecycle;

    @Autowired
    ArtifactRunLayout runLayout;

    @Test
    void discoveryReportsAppearInTheRunManifest() throws IOException {
        discovery.run();
        reportingLifecycle.stop();

        JsonNode manifest = new ObjectMapper().readTree(runLayout.runRoot().resolve("manifest.json").toFile());
        var discoveryEntries = StreamSupport.stream(manifest.get("artifacts").spliterator(), false)
                .filter(entry -> entry.get("source").asText().equals("module-api-discovery"))
                .toList();

        assertThat(discoveryEntries).extracting(entry -> entry.get("name").asText())
                .containsExactly("report.json", "report.md");
        JsonNode reportJson = discoveryEntries.getFirst();
        assertThat(reportJson.get("metadata").get("healthy").asText()).isEqualTo("true");
        assertThat(reportJson.get("metadata").get("location").asText())
                .isEqualTo("build/api-discovery/producer-reporting/report.json");
        assertThat(Path.of(reportJson.get("metadata").get("location").asText())).exists();
        assertThat(Files.readString(runLayout.runRoot().resolve("summary.md"))).contains("module-api-discovery");
    }
}
