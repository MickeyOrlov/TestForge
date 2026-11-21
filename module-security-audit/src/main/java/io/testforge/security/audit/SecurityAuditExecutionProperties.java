package io.testforge.security.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Local process I/O and lifecycle limits; this is not an operating-system sandbox. */
@ConfigurationProperties(prefix = "forge.security-audit.execution")
public record SecurityAuditExecutionProperties(
        Boolean enabled,
        String workDir,
        Integer maxOutputBytes,
        Integer cleanupSeconds,
        Integer maxChildren) {
    public SecurityAuditExecutionProperties {
        enabled = Boolean.TRUE.equals(enabled);
        workDir = workDir == null ? "build/security-audit-work" : workDir;
        maxOutputBytes = maxOutputBytes == null ? 2097152 : maxOutputBytes;
        cleanupSeconds = cleanupSeconds == null ? 3 : cleanupSeconds;
        maxChildren = maxChildren == null ? 32 : maxChildren;
        if (workDir.isBlank()
                || maxOutputBytes < 1
                || maxOutputBytes > 8388608
                || cleanupSeconds < 1
                || cleanupSeconds > 10
                || maxChildren < 1
                || maxChildren > 128)
            throw SecurityAuditProfile.invalid("audit process limits exceed supported bounds");
    }
}
