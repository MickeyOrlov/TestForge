package io.testforge.reporting;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

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
}
