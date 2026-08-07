package io.testforge.api.discovery;

import io.testforge.api.discovery.ApiDiscoveryProperties.ProbeProperties;
import io.testforge.api.discovery.ApiDiscoveryProperties.UnsafeProperties;
import io.testforge.api.discovery.EndpointDescriptor.ParameterDescriptor;
import java.util.List;
import java.util.function.Predicate;
import org.springframework.util.AntPathMatcher;

/**
 * Decides what may be called. This is the module's safety gate, and every rule
 * in it is a rule about what <em>not</em> to do.
 *
 * <p>The starting position is that nothing is called: {@code probe.enabled} is
 * false, so the first run produces a catalog and no requests. Once probing is
 * on, only {@code probe.methods} — {@code GET} by default — goes out. Anything
 * else needs four independently configured keys, and even then it may only use
 * parameter values a human typed into the configuration.
 *
 * <p>A word on {@code deny-paths}: it is a net, not a guarantee. No keyword
 * list catches {@code GET /orders/{id}/cancel}. What actually protects a
 * project is that probing is off until someone reads the catalog and turns it
 * on.
 */
public class ProbePolicy {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final ProbeProperties properties;
    private final ParameterResolution parameters;

    public ProbePolicy(ProbeProperties properties, ParameterResolution parameters) {
        this.properties = properties;
        this.parameters = parameters;
    }

    /**
     * @param alreadyAllowed how many endpoints this run already plans to call,
     *                       so {@code max-endpoints} caps a generated or
     *                       hostile document
     */
    public ProbeDecision decide(EndpointDescriptor endpoint, int alreadyAllowed) {
        ResolvedParameters resolved = parameters.resolve(endpoint);

        if (!properties.enabled()) {
            return ProbeDecision.skip(SkipReason.DISABLED_PROBE, resolved);
        }
        if (matchesAny(properties.denyPaths(), endpoint.path())) {
            return ProbeDecision.skip(SkipReason.PATH_DENIED, resolved);
        }
        if (!matchesAny(properties.includePaths(), endpoint.path())) {
            return ProbeDecision.skip(SkipReason.PATH_NOT_INCLUDED, resolved);
        }
        if (optedOut(endpoint)) {
            return ProbeDecision.skip(SkipReason.VENDOR_OPT_OUT, resolved);
        }
        if (endpoint.deprecated() && properties.skipDeprecated()) {
            return ProbeDecision.skip(SkipReason.DEPRECATED, resolved);
        }

        boolean safeMethod = properties.methods().contains(endpoint.method());
        if (!safeMethod) {
            ProbeDecision refusal = refuseUnsafe(endpoint, resolved);
            if (refusal != null) {
                return refusal;
            }
        }

        List<String> missingPath = missing(endpoint.pathParameters(), resolved.path().keySet()::contains);
        if (!missingPath.isEmpty()) {
            return ProbeDecision.skip(SkipReason.MISSING_PATH_PARAMETER, String.join(", ", missingPath), resolved);
        }
        List<String> missingQuery = missing(endpoint.requiredQueryParameters(), resolved.query().keySet()::contains);
        if (!missingQuery.isEmpty()) {
            return ProbeDecision.skip(SkipReason.MISSING_REQUIRED_PARAM, String.join(", ", missingQuery), resolved);
        }

        // an unsafe request may only be built from values a person chose: this
        // is what stops a future harvesting resolver from finding a real order
        // id and handing it to DELETE
        if (!safeMethod) {
            List<String> foreign = resolved.usedSources().stream()
                    .filter(source -> !ConfiguredParameterResolver.SOURCE.equals(source))
                    .toList();
            if (!foreign.isEmpty()) {
                return ProbeDecision.skip(
                        SkipReason.UNSAFE_PARAMETER_SOURCE, "resolved from " + String.join(", ", foreign), resolved);
            }
        }

        if (alreadyAllowed >= properties.maxEndpoints()) {
            return ProbeDecision.skip(SkipReason.MAX_ENDPOINTS_REACHED, resolved);
        }

        return ProbeDecision.allow(resolved);
    }

    /** Returns the refusal for a method outside {@code probe.methods}, or null when every gate is open. */
    private ProbeDecision refuseUnsafe(EndpointDescriptor endpoint, ResolvedParameters resolved) {
        UnsafeProperties unsafe = properties.unsafe();

        if (!unsafe.enabled()) {
            return ProbeDecision.skip(SkipReason.UNSAFE_METHOD, endpoint.method() + " needs probe.unsafe.enabled",
                    resolved);
        }
        if (!unsafe.methods().contains(endpoint.method())) {
            return ProbeDecision.skip(SkipReason.UNSAFE_METHOD,
                    endpoint.method() + " is not in probe.unsafe.methods", resolved);
        }
        if (!matchesAny(unsafe.includePaths(), endpoint.path())) {
            return ProbeDecision.skip(SkipReason.UNSAFE_METHOD,
                    "path is not in probe.unsafe.include-paths", resolved);
        }
        if ("DELETE".equals(endpoint.method()) && !unsafe.allowDelete()) {
            return ProbeDecision.skip(SkipReason.DELETE_NOT_ALLOWED, resolved);
        }
        return null;
    }

    private boolean optedOut(EndpointDescriptor endpoint) {
        return !endpoint.operation().path(properties.optOutExtension()).asBoolean(true);
    }

    private List<String> missing(List<ParameterDescriptor> required, Predicate<String> resolved) {
        return required.stream()
                .map(ParameterDescriptor::name)
                .filter(name -> !resolved.test(name))
                .toList();
    }

    private boolean matchesAny(List<String> patterns, String path) {
        return patterns.stream().anyMatch(pattern -> MATCHER.match(pattern, path));
    }
}
