package io.testforge.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.api.discovery.ApiDiscoveryReport;
import io.testforge.api.discovery.ApiDiscoveryRunner;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        classes = SmokeTestApplication.class,
        properties = {
                "forge.api-discovery.enabled=true",
                "forge.api-discovery.output-dir=build/api-discovery-smoke/current",
                "forge.api-discovery.baseline-dir=build/api-discovery-smoke/baseline",
                "forge.api-discovery.specs.demo.location=classpath:/openapi/demo-api.yaml"
        })
class PublishedApiDiscoverySmokeTest {

    @Autowired
    ApiDiscoveryRunner discovery;

    @Test
    void discoversOpenApiFromPublishedLibrary() {
        ApiDiscoveryReport report = discovery.assertHealthy();

        assertThat(report.healthy()).isTrue();
        assertThat(report.specs()).singleElement().satisfies(spec -> {
            assertThat(spec.specId()).isEqualTo("demo");
            assertThat(spec.endpoints()).isEqualTo(2);
            assertThat(Path.of(spec.catalogArtifact())).exists();
            assertThat(spec.shapes()).isNotEmpty();
        });
        assertThat(Path.of(report.reportJson())).exists();
        assertThat(Path.of(report.reportMarkdown())).exists();
    }
}
