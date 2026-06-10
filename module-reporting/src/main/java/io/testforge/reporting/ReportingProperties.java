package io.testforge.reporting;

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
 * </pre>
 */
@ConfigurationProperties(prefix = "forge.reporting")
public record ReportingProperties(ResourceMonitor resourceMonitor) {

    public ReportingProperties {
        if (resourceMonitor == null) {
            resourceMonitor = new ResourceMonitor(false, Duration.ofSeconds(2));
        }
    }

    public record ResourceMonitor(boolean enabled, Duration period) {

        public ResourceMonitor {
            if (period == null || period.isZero() || period.isNegative()) {
                period = Duration.ofSeconds(2);
            }
        }
    }
}
