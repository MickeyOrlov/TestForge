package io.testforge.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Optional review gate; all files are local and review updates are explicit. */
@ConfigurationProperties(prefix = "forge.security.business.policy")
public record BusinessPolicyProperties(
        Boolean required,
        String reviewDir,
        Integer maxOperations,
        Integer maxSchemaNodes,
        Integer maxDepth,
        Integer maxReviewBytes) {
    public BusinessPolicyProperties {
        required = Boolean.TRUE.equals(required);
        reviewDir = reviewDir == null ? "build/security/policy-reviews" : reviewDir;
        maxOperations = bound(maxOperations, 500, 5000);
        maxSchemaNodes = bound(maxSchemaNodes, 20000, 100000);
        maxDepth = bound(maxDepth, 32, 100);
        maxReviewBytes = bound(maxReviewBytes, 8388608, 67108864);
    }

    private static int bound(Integer value, int fallback, int max) {
        int result = value == null ? fallback : value;
        if (result < 1 || result > max)
            throw new BusinessSecurityException("business policy limit outside supported range");
        return result;
    }
}
