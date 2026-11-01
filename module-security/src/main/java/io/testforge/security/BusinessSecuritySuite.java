package io.testforge.security;

import com.fasterxml.jackson.databind.JsonNode;

import io.testforge.api.explorer.PreparedRequest;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Declarative, ordered security probes. Runtime values stay in a case-local binding map. */
public record BusinessSecuritySuite(
        String id,
        String version,
        List<String> roles,
        Set<Kind> checks,
        List<Case> cases,
        List<Exclusion> exclusions,
        Map<String, String> tenants) {
    public enum Kind {
        PROPERTY_READ,
        PROPERTY_WRITE,
        OBJECT_ACCESS,
        TENANT_ACCESS,
        TRANSITION,
        REPLAY
    }

    public enum Phase {
        CONTROL,
        PROBE,
        VERIFY
    }

    public enum Match {
        ABSENT,
        PRESENT,
        EQUALS
    }

    public BusinessSecuritySuite(
            String id,
            String version,
            List<String> roles,
            Set<Kind> checks,
            List<Case> cases,
            List<Exclusion> exclusions) {
        this(id, version, roles, checks, cases, exclusions, Map.of());
    }

    public BusinessSecuritySuite {
        tenants = Map.copyOf(tenants == null ? Map.of() : tenants);
        tenants.forEach(
                (role, tenant) -> {
                    identifier(role);
                    identifier(tenant);
                });
        id = identifier(id);
        version = identifier(version);
        roles = List.copyOf(roles);
        roles.forEach(BusinessSecuritySuite::identifier);
        if (roles.isEmpty() || roles.stream().distinct().count() != roles.size())
            throw new IllegalArgumentException("business suite needs distinct explicit roles");
        checks = Set.copyOf(checks);
        if (checks.isEmpty())
            throw new IllegalArgumentException("business suite needs check kinds");
        cases = List.copyOf(cases == null ? List.of() : cases);
        exclusions = List.copyOf(exclusions == null ? List.of() : exclusions);
    }

    /**
     * Operation and role identify the cell credited by this case, not its incidental setup calls.
     */
    public record Case(
            String id,
            Kind kind,
            String method,
            String path,
            String role,
            List<Step> steps,
            List<Step> cleanup) {
        public Case {
            id = identifier(id);
            if (kind == null) throw new IllegalArgumentException("business case needs a kind");
            method = BusinessSecuritySuite.method(method);
            SecurityTargetPolicy.requireRelative(path);
            role = identifier(role);
            steps = List.copyOf(steps);
            cleanup = List.copyOf(cleanup == null ? List.of() : cleanup);
        }

        @Override
        public String toString() {
            return "BusinessSecurityCase[" + id + "]";
        }
    }

    /** Body is JSON; an entire textual ${name} node binds a value without string interpolation. */
    public record Step(
            String id,
            Phase phase,
            String role,
            PreparedRequest request,
            Set<Integer> statuses,
            List<Assertion> assertions,
            Map<String, String> captures) {
        public Step {
            id = identifier(id);
            role = identifier(role);
            if (phase == null || request == null)
                throw new IllegalArgumentException("business step needs phase and request");
            statuses = Set.copyOf(statuses);
            if (statuses.isEmpty()
                    || statuses.stream().anyMatch(s -> s < 200 || s >= 500 || s == 429))
                throw new IllegalArgumentException(
                        "business expected statuses must be deliberate 2xx/3xx/4xx");
            if (phase != Phase.PROBE && statuses.stream().anyMatch(s -> s >= 300))
                throw new IllegalArgumentException(
                        "controls and verification require successful statuses");
            assertions = List.copyOf(assertions == null ? List.of() : assertions);
            captures = Map.copyOf(captures == null ? Map.of() : captures);
            captures.forEach(
                    (name, pointer) -> {
                        identifier(name);
                        pointer(pointer);
                    });
        }

        @Override
        public String toString() {
            return "BusinessSecurityStep[" + id + "]";
        }
    }

    /** JSON Pointer, not JSONPath; EQUALS supports a literal JSON value or an entire ${binding}. */
    public record Assertion(String pointer, Match match, JsonNode expected) {
        public Assertion {
            BusinessSecuritySuite.pointer(pointer);
            if (match == null || match == Match.EQUALS && expected == null)
                throw new IllegalArgumentException(
                        "business assertion needs a match and expected value");
        }

        @Override
        public String toString() {
            return "BusinessSecurityAssertion[redacted]";
        }
    }

    /** Reasons are retained in the coverage artifact: use a ticket/policy reference, never data. */
    public record Exclusion(Kind kind, String method, String path, String role, String reason) {
        public Exclusion {
            if (kind == null) throw new IllegalArgumentException("business exclusion needs a kind");
            method = BusinessSecuritySuite.method(method);
            SecurityTargetPolicy.requireRelative(path);
            role = identifier(role);
            reason = identifier(reason);
        }
    }

    static String identifier(String value) {
        if (value == null || !value.matches("[a-zA-Z][a-zA-Z0-9_-]{0,63}"))
            throw new IllegalArgumentException(
                    "business identifiers must be non-sensitive policy aliases");
        return value;
    }

    static String method(String method) {
        if (method == null
                || !Set.of("GET", "HEAD", "OPTIONS", "POST", "PUT", "PATCH", "DELETE")
                        .contains(method.toUpperCase(Locale.ROOT)))
            throw new IllegalArgumentException("unsupported business HTTP method");
        return method.toUpperCase(Locale.ROOT);
    }

    static void pointer(String value) {
        if (value == null || value.length() > 512 || !value.isEmpty() && !value.startsWith("/"))
            throw new IllegalArgumentException("business assertions require JSON Pointers");
        try {
            com.fasterxml.jackson.core.JsonPointer.compile(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid business JSON Pointer");
        }
    }
}
