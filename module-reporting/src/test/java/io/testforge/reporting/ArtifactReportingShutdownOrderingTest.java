package io.testforge.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.SmartLifecycle;

/**
 * Pins the shutdown ordering between artifact reporting and the producers that publish
 * from their own shutdown callbacks.
 *
 * <p>{@link ResourceUsageMonitor#stop()} publishes its stats through the sink while the
 * context is closing. {@link ArtifactReportingLifecycle} latches after it writes the
 * manifest once, so if it stopped before the monitor, that artifact would be dropped
 * silently and permanently. These tests fail if that ordering ever inverts.
 */
class ArtifactReportingShutdownOrderingTest {

    @Test
    void artifactPublishedDuringShutdownStillReachesTheManifest(@TempDir Path tmp) throws Exception {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TestForgeReportingAutoConfiguration.class))
                .withPropertyValues(
                        "forge.reporting.artifacts.enabled=true",
                        "forge.reporting.artifacts.dir=" + tmp.toAbsolutePath(),
                        "forge.reporting.artifacts.run-id=ordering-run",
                        "forge.reporting.resource-monitor.enabled=true",
                        "forge.reporting.resource-monitor.period=10ms")
                .run(context -> Thread.sleep(120));

        Path manifest = tmp.resolve("ordering-run").resolve("manifest.json");
        assertThat(manifest).exists();

        String body = Files.readString(manifest);
        assertThat(body)
                .as("resource-usage is published from ResourceUsageMonitor.stop(), i.e. during "
                        + "shutdown; it must still be in the manifest")
                .contains("resource-usage");
        assertThat(body).doesNotContain(tmp.toAbsolutePath().toString());
    }

    @Test
    void reportingLifecycleStopsAfterProducerLifecycles(@TempDir Path tmp) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TestForgeReportingAutoConfiguration.class))
                .withPropertyValues(
                        "forge.reporting.artifacts.enabled=true",
                        "forge.reporting.artifacts.dir=" + tmp.toAbsolutePath(),
                        "forge.reporting.resource-monitor.enabled=true")
                .run(context -> {
                    SmartLifecycle reporting = context.getBean(ArtifactReportingLifecycle.class);
                    SmartLifecycle producer = context.getBean(ResourceUsageMonitorLifecycle.class);

                    // Spring stops lifecycle beans in DESCENDING phase order, so the
                    // reporting lifecycle must sit in a strictly lower phase to stop last.
                    assertThat(reporting.getPhase())
                            .as("reporting must stop after producers that publish at shutdown")
                            .isLessThan(producer.getPhase());
                });
    }
}
