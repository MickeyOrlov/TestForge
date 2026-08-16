package io.testforge.reporting;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reporting and diagnostics settings.
 *
 * <pre>
 * forge:
 *   reporting:
 *     resource-monitor:
 *       enabled: true
 *       period: 2s
 *     artifacts:
 *       enabled: true
 *       dir: build/testforge-artifacts
 *       run-id: run-123
 * </pre>
 */
@ConfigurationProperties(prefix = "forge.reporting")
public record ReportingProperties(ResourceMonitor resourceMonitor, Artifacts artifacts) {

    public ReportingProperties {
        if (resourceMonitor == null) {
            resourceMonitor = new ResourceMonitor(false, Duration.ofSeconds(2));
        }
        if (artifacts == null) {
            artifacts = new Artifacts(false, Path.of("build/testforge-artifacts"), null);
        }
    }

    public record ResourceMonitor(boolean enabled, Duration period) {

        public ResourceMonitor {
            if (period == null || period.isZero() || period.isNegative()) {
                period = Duration.ofSeconds(2);
            }
        }
    }

    public record Artifacts(boolean enabled, Path dir, String runId) {

        public Artifacts {
            if (dir == null) {
                dir = Path.of("build/testforge-artifacts");
            }
        }
    }
}

