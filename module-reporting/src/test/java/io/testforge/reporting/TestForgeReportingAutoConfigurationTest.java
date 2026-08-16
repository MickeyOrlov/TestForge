package io.testforge.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.testforge.artifact.ArtifactSink;
import io.testforge.artifact.TestArtifact;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class TestForgeReportingAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TestForgeReportingAutoConfiguration.class));

    @Test
    void disabledByDefault_noLayoutOrWriters_andSinkIsNoOp() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(ArtifactRunLayout.class);
            assertThat(context).doesNotHaveBean(RunArtifactSink.class);
            assertThat(context).doesNotHaveBean(ArtifactManifestWriter.class);
            assertThat(context).doesNotHaveBean(ArtifactSummaryWriter.class);
            assertThat(context).doesNotHaveBean(ArtifactReportingLifecycle.class);

            assertThat(context).hasSingleBean(ArtifactSink.class);
            assertThat(context.getBean(ArtifactSink.class)).isSameAs(ArtifactSink.NO_OP);

            assertThat(context).hasSingleBean(ResourceUsageMonitor.class);
            assertThat(context).doesNotHaveBean(ResourceUsageMonitorLifecycle.class);
        });
    }

    @Test
    void enabled_wiresRealSinkAndLayoutAndWriters() {
        contextRunner
                .withPropertyValues("forge.reporting.artifacts.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(ArtifactRunLayout.class);
                    assertThat(context).hasSingleBean(ArtifactManifestWriter.class);
                    assertThat(context).hasSingleBean(ArtifactSummaryWriter.class);
                    assertThat(context).hasSingleBean(ArtifactReportingLifecycle.class);

                    assertThat(context).hasSingleBean(ArtifactSink.class);
                    assertThat(context.getBean(ArtifactSink.class)).isInstanceOf(RunArtifactSink.class);

                    assertThat(context).hasSingleBean(ResourceUsageMonitor.class);
                    assertThat(context).doesNotHaveBean(ResourceUsageMonitorLifecycle.class);
                });
    }

    @Test
    void enabled_writesToConfiguredDirAndRunId(@TempDir Path tempDir) {
        Path customDir = tempDir.resolve("custom-artifacts");
        String customRunId = "pinned-run-123";

        contextRunner
                .withPropertyValues(
                        "forge.reporting.artifacts.enabled=true",
                        "forge.reporting.artifacts.dir=" + customDir,
                        "forge.reporting.artifacts.run-id=" + customRunId
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ArtifactRunLayout.class);
                    ArtifactRunLayout layout = context.getBean(ArtifactRunLayout.class);
                    assertThat(layout.baseDir()).isEqualTo(customDir);
                    assertThat(layout.runId()).isEqualTo(customRunId);

                    ArtifactSink sink = context.getBean(ArtifactSink.class);
                    TestArtifact artifact = sink.write("module-test", "diag", "sample.txt", "text/plain", "hello world");

                    assertThat(artifact).isNotNull();
                    assertThat(artifact.file()).startsWith(customDir.resolve(customRunId));
                    assertThat(Files.exists(artifact.file())).isTrue();
                });
    }

    @Test
    void userSuppliedArtifactSink_replacesDefaultWhenEnabled() {
        contextRunner
                .withPropertyValues("forge.reporting.artifacts.enabled=true")
                .withUserConfiguration(CustomArtifactSinkConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(ArtifactSink.class);
                    assertThat(context.getBean(ArtifactSink.class)).isSameAs(CustomArtifactSinkConfiguration.CUSTOM_SINK);
                });
    }

    @Test
    void userSuppliedArtifactSink_whenEnabled_recordsProblemInManifestOnShutdown(@TempDir Path tempDir) {
        contextRunner
                .withPropertyValues(
                        "forge.reporting.artifacts.enabled=true",
                        "forge.reporting.artifacts.dir=" + tempDir,
                        "forge.reporting.artifacts.run-id=foreign-sink-run"
                )
                .withUserConfiguration(CustomArtifactSinkConfiguration.class)
                .run(context -> {
                    ArtifactReportingLifecycle lifecycle = context.getBean(ArtifactReportingLifecycle.class);
                    lifecycle.start();
                    lifecycle.stop();

                    Path manifestPath = tempDir.resolve("foreign-sink-run").resolve("manifest.json");
                    assertThat(Files.exists(manifestPath)).isTrue();
                    String manifestContent = Files.readString(manifestPath);
                    assertThat(manifestContent).contains("reportingProblems");
                    assertThat(manifestContent).contains("Artifacts could not be collected from foreign sink");
                });
    }

    @Test
    void userSuppliedArtifactSink_replacesNoOpWhenDisabled() {
        contextRunner
                .withUserConfiguration(CustomArtifactSinkConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(ArtifactSink.class);
                    assertThat(context.getBean(ArtifactSink.class)).isSameAs(CustomArtifactSinkConfiguration.CUSTOM_SINK);
                });
    }

    @Test
    void resourceMonitorBehavior_isUnaffected() {
        contextRunner
                .withPropertyValues("forge.reporting.resource-monitor.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(ResourceUsageMonitor.class);
                    assertThat(context).hasSingleBean(ResourceUsageMonitorLifecycle.class);

                    // Artifact reporting remains disabled by default
                    assertThat(context).doesNotHaveBean(ArtifactRunLayout.class);
                    assertThat(context.getBean(ArtifactSink.class)).isSameAs(ArtifactSink.NO_OP);
                });
    }

    @Test
    void lifecycleWritesManifestAndSummaryOnShutdown(@TempDir Path tempDir) {
        contextRunner
                .withPropertyValues(
                        "forge.reporting.artifacts.enabled=true",
                        "forge.reporting.artifacts.dir=" + tempDir,
                        "forge.reporting.artifacts.run-id=shutdown-run"
                )
                .run(context -> {
                    ArtifactSink sink = context.getBean(ArtifactSink.class);
                    sink.write("module-flow", "trace", "flow.json", "application/json", "{\"step\":1}");

                    ArtifactReportingLifecycle lifecycle = context.getBean(ArtifactReportingLifecycle.class);
                    lifecycle.start();
                    assertThat(lifecycle.isRunning()).isTrue();

                    // Trigger lifecycle stop (simulates Spring context shutdown)
                    lifecycle.stop();
                    assertThat(lifecycle.isRunning()).isFalse();

                    Path runRoot = tempDir.resolve("shutdown-run");
                    assertThat(Files.exists(runRoot.resolve("manifest.json"))).isTrue();
                    assertThat(Files.exists(runRoot.resolve("summary.md"))).isTrue();
                });
    }

    @Test
    void reportingPropertiesDefaults() {
        ReportingProperties defaultProps = new ReportingProperties(null, null);
        assertThat(defaultProps.resourceMonitor().enabled()).isFalse();
        assertThat(defaultProps.artifacts().enabled()).isFalse();
        assertThat(defaultProps.artifacts().dir()).isEqualTo(Path.of("build/testforge-artifacts"));
        assertThat(defaultProps.artifacts().runId()).isNull();
    }

    @Configuration
    static class CustomArtifactSinkConfiguration {
        static final ArtifactSink CUSTOM_SINK = new ArtifactSink() {
            @Override
            public Path directoryFor(String source) {
                return Path.of("custom");
            }

            @Override
            public void register(TestArtifact artifact) {
            }

            @Override
            public TestArtifact write(String source, String category, String name, String mediaType, String content) {
                return new TestArtifact(source, category, name, Path.of("custom", name), mediaType, Instant.now(), Map.of());
            }
        };

        @Bean
        ArtifactSink customArtifactSink() {
            return CUSTOM_SINK;
        }
    }
}
