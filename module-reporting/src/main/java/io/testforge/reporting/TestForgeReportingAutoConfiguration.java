package io.testforge.reporting;

import io.testforge.artifact.ArtifactSink;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration for TestForge reporting and artifact collection.
 *
 * <p>Configures resource usage monitoring and run-scoped artifact reporting. When
 * {@code forge.reporting.artifacts.enabled=true}, wires the {@link ArtifactRunLayout},
 * {@link RunArtifactSink}, {@link ArtifactManifestWriter}, {@link ArtifactSummaryWriter},
 * and {@link ArtifactReportingLifecycle}.
 *
 * <p>When artifact reporting is disabled (the default), provides {@link ArtifactSink#NO_OP}
 * as the {@link ArtifactSink} bean so producing modules can inject {@link ArtifactSink}
 * safely without null checks or optional wrapping.
 */
@AutoConfiguration
@EnableConfigurationProperties(ReportingProperties.class)
public class TestForgeReportingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ResourceUsageMonitor resourceUsageMonitor() {
        return new ResourceUsageMonitor();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "forge.reporting.resource-monitor",
            name = "enabled",
            havingValue = "true")
    public ResourceUsageMonitorLifecycle resourceUsageMonitorLifecycle(
            ResourceUsageMonitor monitor,
            ReportingProperties properties) {
        return new ResourceUsageMonitorLifecycle(monitor, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "forge.reporting.artifacts",
            name = "enabled",
            havingValue = "true")
    public ArtifactRunLayout artifactRunLayout(ReportingProperties properties) {
        return new ArtifactRunLayout(
                properties.artifacts().dir(),
                properties.artifacts().runId());
    }

    /**
     * Real run-scoped artifact sink active when artifact reporting is enabled.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "forge.reporting.artifacts",
            name = "enabled",
            havingValue = "true")
    public ArtifactSink artifactSink(ArtifactRunLayout layout) {
        return new RunArtifactSink(layout);
    }

    /**
     * Fallback no-op artifact sink active when artifact reporting is disabled.
     * Ensures injecting modules receive a usable non-null instance without needing null checks.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "forge.reporting.artifacts",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true)
    public ArtifactSink noOpArtifactSink() {
        return ArtifactSink.NO_OP;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "forge.reporting.artifacts",
            name = "enabled",
            havingValue = "true")
    public ArtifactManifestWriter artifactManifestWriter() {
        return new ArtifactManifestWriter();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "forge.reporting.artifacts",
            name = "enabled",
            havingValue = "true")
    public ArtifactSummaryWriter artifactSummaryWriter() {
        return new ArtifactSummaryWriter();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "forge.reporting.artifacts",
            name = "enabled",
            havingValue = "true")
    public ArtifactReportingLifecycle artifactReportingLifecycle(
            ArtifactSink sink,
            ArtifactRunLayout layout,
            ArtifactManifestWriter manifestWriter,
            ArtifactSummaryWriter summaryWriter) {
        return new ArtifactReportingLifecycle(sink, layout, manifestWriter, summaryWriter);
    }
}

