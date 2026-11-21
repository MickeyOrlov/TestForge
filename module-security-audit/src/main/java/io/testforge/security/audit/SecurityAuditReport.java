package io.testforge.security.audit;

import com.fasterxml.jackson.databind.JsonNode;

import io.testforge.security.audit.SecurityAuditProfile.Engine;
import io.testforge.security.audit.SecurityAuditProfile.Environment;
import io.testforge.security.audit.SecurityAuditProfile.Layer;
import io.testforge.security.audit.SecurityAuditProfile.TargetKind;

import java.util.List;

/** v1 audit envelope over projected security report v1; does not implement a second baseline. */
public record SecurityAuditReport(
        int formatVersion,
        String mode,
        String profile,
        Environment environment,
        boolean enabled,
        boolean measured,
        boolean complete,
        boolean healthy,
        List<Job> jobs) {
    public enum Status {
        MEASURED,
        INCOMPLETE,
        DISABLED,
        NOT_APPLICABLE,
        BLOCKED
    }

    public record Job(
            String asset,
            Layer layer,
            TargetKind kind,
            Engine engine,
            String version,
            String sourceVersion,
            Status status,
            String reason,
            String sourceFormat,
            JsonNode security) {
        public Job {
            security = security == null ? null : security.deepCopy();
        }

        @Override
        public JsonNode security() {
            return security == null ? null : security.deepCopy();
        }
    }

    public SecurityAuditReport {
        jobs = List.copyOf(jobs);
    }
}
