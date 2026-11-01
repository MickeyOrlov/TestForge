package io.testforge.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** Independent opt-in and hard bounds for project-declared business probes. */
@ConfigurationProperties(prefix = "forge.security.business")
public record BusinessSecurityProperties(
        Boolean enabled,
        Boolean testEnvironment,
        List<String> allowedHosts,
        Boolean writeChecks,
        Boolean allowUnsafeMethods,
        Integer maxRequests,
        Integer maxCases,
        Integer maxSteps,
        Integer maxCells,
        Integer maxBodyBytes,
        Duration timeout) {
    public BusinessSecurityProperties {
        enabled = Boolean.TRUE.equals(enabled);
        testEnvironment = Boolean.TRUE.equals(testEnvironment);
        writeChecks = Boolean.TRUE.equals(writeChecks);
        allowUnsafeMethods = Boolean.TRUE.equals(allowUnsafeMethods);
        allowedHosts = List.copyOf(allowedHosts == null ? List.of() : allowedHosts);
        maxRequests = bound(maxRequests, 1000, 10000);
        maxCases = bound(maxCases, 100, 1000);
        maxSteps = bound(maxSteps, 50, 200);
        maxCells = bound(maxCells, 10000, 50000);
        maxBodyBytes = bound(maxBodyBytes, 1048576, 8388608);
        timeout = timeout == null ? Duration.ofMinutes(5) : timeout;
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofHours(1)) > 0)
            throw new IllegalArgumentException(
                    "business timeout must be positive and at most one hour");
    }

    private static int bound(Integer value, int fallback, int maximum) {
        int result = value == null ? fallback : value;
        if (result < 1 || result > maximum)
            throw new IllegalArgumentException("business limit is outside its supported range");
        return result;
    }
}
