package io.testforge.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "forge.security.artifacts-scan")
public record SecurityArtifactProperties(
        Boolean enabled,
        List<String> roots,
        Long maxFileBytes,
        Long maxTotalBytes,
        Integer maxFiles) {
    public SecurityArtifactProperties {
        enabled = Boolean.TRUE.equals(enabled);
        roots = List.copyOf(roots == null ? List.of("build/testforge-artifacts") : roots);
        for (String root : roots) {
            try {
                java.nio.file.Path.of(root);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("artifact roots contain an invalid path");
            }
        }
        maxFileBytes = maxFileBytes == null ? 8L * 1024 * 1024 : maxFileBytes;
        maxTotalBytes = maxTotalBytes == null ? 64L * 1024 * 1024 : maxTotalBytes;
        maxFiles = maxFiles == null ? 10000 : maxFiles;
        if (maxFileBytes < 1
                || maxFileBytes > Integer.MAX_VALUE - 1
                || maxTotalBytes < 1
                || maxFiles < 1)
            throw new IllegalArgumentException(
                    "artifact scan budgets must be positive and bounded");
    }
}
