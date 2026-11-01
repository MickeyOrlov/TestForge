package io.testforge.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Offline generation bounds. Generation is explicit and never enables business probes. */
@ConfigurationProperties(prefix = "forge.security.scenarios")
public record BusinessScenarioProperties(
        Integer maxOperations,
        Integer maxLinks,
        Integer maxChain,
        Integer maxDrafts,
        Boolean pathHints) {
    public BusinessScenarioProperties {
        maxOperations = bound(maxOperations, 500, 5000);
        maxLinks = bound(maxLinks, 2000, 10000);
        maxChain = bound(maxChain, 8, 30);
        maxDrafts = bound(maxDrafts, 50, 500);
        pathHints = pathHints == null || pathHints;
    }

    private static int bound(Integer value, int fallback, int maximum) {
        int result = value == null ? fallback : value;
        if (result < 1 || result > maximum)
            throw new BusinessSecurityException(
                    "scenario generation limit outside supported range");
        return result;
    }
}
