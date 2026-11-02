package io.testforge.security.audit;

import com.fasterxml.jackson.databind.JsonNode;

import io.testforge.security.audit.SecurityAuditProfile.Engine;

/** Immutable projected upstream evidence. Raw source data is not retained. */
public final class SecurityAuditEvidence {
    private final String asset;
    private final Engine engine;
    private final String version;
    private final JsonNode security;
    private final String failure;

    SecurityAuditEvidence(
            String asset, Engine engine, String version, JsonNode security, String failure) {
        this.asset = SecurityAuditProfile.alias(asset);
        new SecurityAuditProfile.EnginePolicy(engine, true, version);
        this.engine = engine;
        this.version = version;
        this.security = security == null ? null : security.deepCopy();
        this.failure = failure;
    }

    public String asset() {
        return asset;
    }

    public Engine engine() {
        return engine;
    }

    public String version() {
        return version;
    }

    public JsonNode securityReport() {
        return security == null ? null : security.deepCopy();
    }

    public String failure() {
        return failure;
    }

    @Override
    public String toString() {
        return "SecurityAuditEvidence[" + asset + "," + engine + ",redacted]";
    }
}
