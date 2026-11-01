package io.testforge.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Set;

@ConfigurationProperties(prefix = "forge.security.active")
public record SecurityActiveProperties(
        Boolean enabled,
        Boolean testEnvironment,
        List<String> allowedHosts,
        Integer maxRequestsPerSecond,
        Integer maxRequests,
        Duration timeBudget,
        Set<Integer> rules,
        String image,
        String role,
        Mode mode,
        Integer maxResponseBytes) {
    public enum Mode {
        API,
        BASELINE
    }

    public SecurityActiveProperties {
        enabled = Boolean.TRUE.equals(enabled);
        testEnvironment = Boolean.TRUE.equals(testEnvironment);
        allowedHosts = List.copyOf(allowedHosts == null ? List.of() : allowedHosts);
        maxRequestsPerSecond = maxRequestsPerSecond == null ? 10 : maxRequestsPerSecond;
        maxRequests = maxRequests == null ? 1000 : maxRequests;
        timeBudget = timeBudget == null ? Duration.ofMinutes(20) : timeBudget;
        rules = Set.copyOf(rules == null ? Set.of(40003, 40012) : rules);
        image =
                image == null
                        ? "zaproxy/zap-stable@sha256:781a2bdaea47324e7bab583e2263f21d257b0aee61ed51521a5be45f5f5081ef"
                        : image;
        mode = mode == null ? Mode.API : mode;
        maxResponseBytes = maxResponseBytes == null ? 2 * 1024 * 1024 : maxResponseBytes;
        if (maxRequestsPerSecond < 1
                || maxRequestsPerSecond > 20
                || maxRequests < 1
                || maxRequests > 10000
                || timeBudget.toSeconds() < 1
                || timeBudget.compareTo(Duration.ofHours(1)) > 0
                || maxResponseBytes < 1
                || maxResponseBytes > 8 * 1024 * 1024)
            throw new IllegalArgumentException("active scan budgets exceed supported bounds");
        if (rules.isEmpty() || !Set.of(40003, 40012).containsAll(rules))
            throw new IllegalArgumentException(
                    "active rules are limited to reviewed CRLF and reflected-XSS checks;"
                            + " DoS/destructive/time-based rules are refused");
        if (!image.matches("[a-zA-Z0-9./_-]+@sha256:[a-f0-9]{64}"))
            throw new IllegalArgumentException("ZAP image must be pinned by digest");
    }
}
