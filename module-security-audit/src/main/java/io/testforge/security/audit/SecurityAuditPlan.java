package io.testforge.security.audit;

import java.util.List;

/** Declaration plan only. No job has executed and this is not a security assessment. */
public record SecurityAuditPlan(
        int formatVersion,
        String profile,
        SecurityAuditProfile.Environment environment,
        boolean executed,
        boolean runnable,
        SecurityAuditProfile.Budget budget,
        List<Job> jobs) {
    public enum Status {
        PLANNED,
        BLOCKED,
        DISABLED,
        NOT_APPLICABLE
    }

    public record Job(
            String asset,
            SecurityAuditProfile.Layer layer,
            SecurityAuditProfile.TargetKind kind,
            SecurityAuditProfile.Engine engine,
            String version,
            Status status,
            String reason) {}

    public SecurityAuditPlan {
        jobs = List.copyOf(jobs);
    }
}
