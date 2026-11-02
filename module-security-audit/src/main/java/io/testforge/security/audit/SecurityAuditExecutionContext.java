package io.testforge.security.audit;

import io.testforge.security.audit.SecurityAuditProfile.Asset;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * Private preparation context. Secrets are case-local stdin material, never arguments/environment.
 */
public final class SecurityAuditExecutionContext implements AutoCloseable {
    @FunctionalInterface
    public interface Secrets {
        byte[] resolve(String reference) throws Exception;
    }

    private final Asset asset;
    private final Path directory;
    private final long deadline;
    private final SecurityAuditCancellation cancellation;
    private byte[] credential = new byte[0];
    private boolean closed;

    SecurityAuditExecutionContext(
            Asset asset, Path directory, long deadline, SecurityAuditCancellation cancellation) {
        this.asset = asset;
        this.directory = directory;
        this.deadline = deadline;
        this.cancellation = cancellation;
    }

    public Asset asset() {
        return asset;
    }

    public Path directory() {
        return directory;
    }

    public long remainingNanos() {
        return Math.max(0, deadline - System.nanoTime());
    }

    public boolean cancelled() {
        return cancellation.cancelled() || remainingNanos() == 0;
    }

    public synchronized byte[] credential() {
        if (closed) throw SecurityAuditProfile.invalid("audit context is closed");
        return credential.clone();
    }

    synchronized void install(byte[] value) {
        if (closed || value == null || value.length == 0 || value.length > 4096)
            throw SecurityAuditProfile.invalid("audit credential is unavailable or exceeds bounds");
        credential = value.clone();
    }

    @Override
    public synchronized void close() {
        closed = true;
        Arrays.fill(credential, (byte) 0);
    }

    @Override
    public String toString() {
        return "SecurityAuditExecutionContext[redacted]";
    }
}
