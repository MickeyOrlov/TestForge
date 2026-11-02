package io.testforge.security.audit;

import io.testforge.security.audit.SecurityAuditProfile.Engine;

import java.util.List;

/** Process receipt, not a network-confinement or scanner-version attestation. */
public record SecurityAuditExecution(
        int formatVersion,
        String isolation,
        List<Attempt> attempts,
        SecurityAuditReport assessment) {
    public enum Outcome {
        SUCCESS,
        ADAPTER_MISSING,
        VERSION_MISMATCH,
        PREREQUISITE_FAILED,
        ADAPTER_FAILED,
        START_FAILED,
        TIMED_OUT,
        CANCELLED,
        OUTPUT_LIMIT,
        PROCESS_LIMIT,
        NONZERO_EXIT,
        INVALID_OUTPUT,
        SECRET_LEAK,
        CLEANUP_FAILED,
        BUDGET_EXHAUSTED
    }

    public record Attempt(
            String asset,
            Engine engine,
            Outcome outcome,
            boolean started,
            Integer exitCode,
            long durationMillis,
            long outputBytes,
            boolean cleanupComplete) {}

    public SecurityAuditExecution {
        attempts = List.copyOf(attempts);
    }
}
