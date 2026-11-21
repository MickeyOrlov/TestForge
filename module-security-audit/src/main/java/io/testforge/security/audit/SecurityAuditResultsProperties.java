package io.testforge.security.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounds for normalized report input and output, not scanner execution. */
@ConfigurationProperties(prefix = "forge.security-audit.results")
public record SecurityAuditResultsProperties(
        String outputDir, Integer maxBytes, Integer maxEntries) {
    public SecurityAuditResultsProperties {
        outputDir = outputDir == null ? "build/security-audit" : outputDir;
        if (outputDir.isBlank())
            throw SecurityAuditProfile.invalid("audit results need an output directory");
        maxBytes = maxBytes == null ? 2 * 1024 * 1024 : maxBytes;
        maxEntries = maxEntries == null ? 10000 : maxEntries;
        if (maxBytes < 1 || maxBytes > 8 * 1024 * 1024 || maxEntries < 1 || maxEntries > 50000)
            throw SecurityAuditProfile.invalid("audit report limits exceed supported bounds");
    }
}
