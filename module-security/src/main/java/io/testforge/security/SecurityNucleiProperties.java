package io.testforge.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties("forge.security.nuclei")
public record SecurityNucleiProperties(
        boolean enabled,
        boolean testEnvironment,
        List<String> allowedHosts,
        Duration timeBudget,
        Integer maxRequests,
        Integer maxRequestsPerSecond) {
    public SecurityNucleiProperties {
        allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
        timeBudget = timeBudget == null ? Duration.ofSeconds(90) : timeBudget;
        maxRequests = maxRequests == null ? 50 : maxRequests;
        maxRequestsPerSecond = maxRequestsPerSecond == null ? 5 : maxRequestsPerSecond;
        if (timeBudget.compareTo(Duration.ofSeconds(1)) < 0
                || timeBudget.compareTo(Duration.ofMinutes(10)) > 0
                || maxRequests < 1
                || maxRequests > 200
                || maxRequestsPerSecond < 1
                || maxRequestsPerSecond > 10) {
            throw new IllegalArgumentException("Nuclei scan budgets exceed supported bounds");
        }
    }

    SecurityScanGateway.Limits limits() {
        return new SecurityScanGateway.Limits(
                maxRequestsPerSecond, maxRequests, timeBudget, 2 * 1024 * 1024);
    }
}
