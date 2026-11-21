package io.testforge.security.audit;

import io.testforge.security.audit.SecurityAuditPlan.Status;
import io.testforge.security.audit.SecurityAuditProfile.Asset;
import io.testforge.security.audit.SecurityAuditProfile.Engine;
import io.testforge.security.audit.SecurityAuditProfile.EnginePolicy;
import io.testforge.security.audit.SecurityAuditProfile.Layer;
import io.testforge.security.audit.SecurityAuditProfile.TargetKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Pure deterministic inventory x engine matrix. Never launches or discovers anything. */
public final class SecurityAuditPlanner {
    private final SecurityAuditProperties properties;

    public SecurityAuditPlanner(SecurityAuditProperties properties) {
        this.properties = properties;
    }

    public SecurityAuditPlan plan(SecurityAuditProfile profile) {
        if (!properties.enabled())
            throw SecurityAuditProfile.invalid(
                    "forge.security-audit.enabled must be true for planning");
        if (profile == null) throw SecurityAuditProfile.invalid("audit profile is required");
        var budget = profile.budget();
        if (profile.assets().size() > properties.maxAssets()
                || (long) profile.assets().size() * Engine.values().length > properties.maxJobs()
                || budget.maxRequests() > properties.maxRequests()
                || budget.timeoutSeconds() > properties.timeoutSeconds()
                || budget.maxConcurrent() > properties.maxConcurrent())
            throw SecurityAuditProfile.invalid("audit plan exceeds administrative ceilings");
        Map<Engine, EnginePolicy> engines =
                profile.engines().stream().collect(Collectors.toMap(EnginePolicy::engine, v -> v));
        Set<String> allowed =
                profile.allowedHosts().stream()
                        .map(SecurityAuditTargets::host)
                        .collect(Collectors.toSet());
        var jobs = new ArrayList<SecurityAuditPlan.Job>();
        for (Asset asset :
                profile.assets().stream().sorted(Comparator.comparing(Asset::id)).toList())
            for (Engine engine : Engine.values()) {
                var config = engines.get(engine);
                Status status;
                String reason;
                if (!supports(engine, asset.layer(), asset.kind())) {
                    status = Status.NOT_APPLICABLE;
                    reason = "engine does not support this layer and target kind";
                } else if (config == null || !config.enabled()) {
                    status = Status.DISABLED;
                    reason = "engine was not explicitly enabled";
                } else {
                    String blocked = blocked(engine, asset, allowed);
                    status = blocked == null ? Status.PLANNED : Status.BLOCKED;
                    reason =
                            blocked == null
                                    ? "declaration complete; runtime prerequisites have not been"
                                            + " checked"
                                    : blocked;
                }
                jobs.add(
                        new SecurityAuditPlan.Job(
                                asset.id(),
                                asset.layer(),
                                asset.kind(),
                                engine,
                                config == null ? null : config.version(),
                                status,
                                reason));
            }
        boolean runnable =
                jobs.stream().anyMatch(j -> j.status() == Status.PLANNED)
                        && jobs.stream().noneMatch(j -> j.status() == Status.BLOCKED);
        return new SecurityAuditPlan(
                1, profile.id(), profile.environment(), false, runnable, budget, jobs);
    }

    static boolean supports(Engine engine, Layer layer, TargetKind kind) {
        return switch (engine) {
            case ZAP, SCHEMATHESIS, TESTFORGE ->
                    layer == Layer.APPLICATION && kind == TargetKind.URL;
            case TRIVY ->
                    layer == Layer.SERVICES
                            && Set.of(TargetKind.IMAGE, TargetKind.REPOSITORY).contains(kind);
            case NUCLEI -> layer == Layer.SERVICES && kind == TargetKind.URL;
            case NMAP -> layer == Layer.NETWORK && kind == TargetKind.HOST;
            case TESTSSL -> layer == Layer.NETWORK && kind == TargetKind.URL;
        };
    }

    private static String blocked(Engine engine, Asset asset, Set<String> allowed) {
        String host =
                switch (asset.kind()) {
                    case URL -> SecurityAuditTargets.url(asset.location()).getHost();
                    case HOST -> asset.location();
                    case IMAGE ->
                            SecurityAuditTargets.url(
                                            "https://"
                                                    + asset.location()
                                                            .substring(
                                                                    0,
                                                                    asset.location().indexOf('/')))
                                    .getHost();
                    case REPOSITORY -> null;
                };
        if (host != null && !allowed.contains(SecurityAuditTargets.host(host)))
            return "target host is outside the explicit allowlist";
        if ((engine == Engine.ZAP || engine == Engine.SCHEMATHESIS) && asset.openApi() == null)
            return "local OpenAPI declaration is required";
        if (engine == Engine.NMAP && asset.ports().isEmpty())
            return "explicit bounded port list is required";
        if (engine == Engine.TESTSSL && !asset.location().startsWith("https://"))
            return "TLS checks require an explicit HTTPS endpoint";
        return null;
    }
}
