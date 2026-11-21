package io.testforge.security.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Administrative ceilings for declaration planning, not evidence of runtime enforcement. */
@ConfigurationProperties(prefix = "forge.security-audit")
public record SecurityAuditProperties(
        Boolean enabled,
        Integer maxAssets,
        Integer maxJobs,
        Integer maxRequests,
        Integer timeoutSeconds,
        Integer maxConcurrent) {
    public SecurityAuditProperties {
        enabled = Boolean.TRUE.equals(enabled);
        maxAssets = bound(maxAssets, 100, 1000);
        maxJobs = bound(maxJobs, 700, 7000);
        maxRequests = bound(maxRequests, 1000, 10000);
        timeoutSeconds = bound(timeoutSeconds, 1200, 3600);
        maxConcurrent = bound(maxConcurrent, 2, 8);
    }

    private static int bound(Integer value, int fallback, int ceiling) {
        int actual = value == null ? fallback : value;
        if (actual < 1 || actual > ceiling)
            throw new IllegalArgumentException("audit limit exceeds supported bounds");
        return actual;
    }
}
