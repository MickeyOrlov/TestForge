package io.testforge.security.audit;

import java.util.List;
import java.util.Set;

/** Private configuration: never serialize profiles into reports or diagnostics. */
public record SecurityAuditProfile(
        String id,
        Environment environment,
        Set<String> allowedHosts,
        List<Asset> assets,
        List<EnginePolicy> engines,
        Budget budget) {
    public enum Environment {
        LOCAL,
        TEST,
        STAGING
    }

    public enum Layer {
        APPLICATION,
        SERVICES,
        NETWORK
    }

    public enum TargetKind {
        URL,
        HOST,
        IMAGE,
        REPOSITORY
    }

    public enum Engine {
        ZAP,
        SCHEMATHESIS,
        TRIVY,
        NUCLEI,
        NMAP,
        TESTSSL,
        TESTFORGE
    }

    public SecurityAuditProfile {
        id = alias(id);
        if (environment == null) throw invalid("audit environment must be explicit");
        allowedHosts = Set.copyOf(allowedHosts == null ? Set.of() : allowedHosts);
        allowedHosts.forEach(SecurityAuditTargets::host);
        assets = List.copyOf(assets == null ? List.of() : assets);
        engines = List.copyOf(engines == null ? List.of() : engines);
        if (assets.isEmpty()
                || assets.size() > 1000
                || engines.size() > Engine.values().length
                || allowedHosts.size() > 1000)
            throw invalid("audit profile scope exceeds bounds or has no assets");
        if (assets.stream().map(Asset::id).distinct().count() != assets.size()
                || engines.stream().map(EnginePolicy::engine).distinct().count() != engines.size())
            throw invalid("audit asset and engine declarations must be unique");
        budget = budget == null ? new Budget(1000, 600, 2) : budget;
    }

    public record Asset(
            String id,
            Layer layer,
            TargetKind kind,
            String location,
            List<Integer> ports,
            String openApi,
            String credentialRef) {
        public Asset {
            id = alias(id);
            if (layer == null || kind == null)
                throw invalid("audit asset needs layer and target kind");
            SecurityAuditTargets.location(kind, location);
            ports = List.copyOf(ports == null ? List.of() : ports);
            if (ports.size() > 100
                    || ports.stream().anyMatch(p -> p < 1 || p > 65535)
                    || ports.stream().distinct().count() != ports.size()
                    || kind != TargetKind.HOST && !ports.isEmpty())
                throw invalid("audit ports must be explicit distinct host ports within bounds");
            if (openApi != null) SecurityAuditTargets.localPath(openApi);
            if (credentialRef != null) alias(credentialRef);
        }

        @Override
        public String toString() {
            return "SecurityAuditAsset[" + id + ",redacted]";
        }
    }

    public record EnginePolicy(Engine engine, boolean enabled, String version) {
        public EnginePolicy {
            if (engine == null) throw invalid("audit engine must be explicit");
            if (version != null
                    && !version.matches(
                            "[0-9]{1,4}\\.[0-9]{1,4}(\\.[0-9]{1,4})?([-+][a-zA-Z0-9.-]{1,40})?"))
                throw invalid("audit engine needs an exact release version");
            if (enabled && version == null)
                throw invalid("enabled audit engine needs an exact release version");
        }
    }

    public record Budget(int maxRequests, int timeoutSeconds, int maxConcurrent) {
        public Budget {
            if (maxRequests < 1
                    || maxRequests > 10000
                    || timeoutSeconds < 1
                    || timeoutSeconds > 3600
                    || maxConcurrent < 1
                    || maxConcurrent > 8) throw invalid("audit budget exceeds supported bounds");
        }
    }

    static String alias(String value) {
        if (value == null || !value.matches("[a-zA-Z][a-zA-Z0-9_-]{0,63}"))
            throw invalid("audit aliases must be non-sensitive structural names");
        return value;
    }

    static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    @Override
    public String toString() {
        return "SecurityAuditProfile[" + id + ",redacted]";
    }
}
