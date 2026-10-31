package io.testforge.api.explorer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Who may call what: every operation of a document, called once per role,
 * checked against a declared expectation.
 *
 * <pre>
 * forge:
 *   access-matrix:
 *     enabled: true
 *     roles: [admin, user, anonymous]   # default: the roles named below
 *     expect:                           # per role, for every operation
 *       admin: allow
 *       user: deny
 *       anonymous: "401"
 *     operations:                       # per operation id, overriding the line above
 *       listTasks:
 *         user: allow
 *     fail-on-undeclared: false         # default
 * </pre>
 *
 * <p>Documents, parameter values and the method safety policy are the
 * explorer's: {@code forge.api-discovery.specs}, {@code forge.api-explorer.parameters},
 * {@code forge.api-explorer.methods} and {@code allow-unsafe-methods}. The
 * reserved role {@code anonymous} sends no token at all.
 */
@ConfigurationProperties(prefix = "forge.access-matrix")
public record AccessMatrixProperties(
        Boolean enabled,
        String outputDir,
        List<String> specs,
        List<String> roles,
        Map<String, String> expect,
        Map<String, Map<String, String>> operations,
        Boolean failOnUndeclared) {

    public AccessMatrixProperties {
        if (enabled == null) {
            enabled = false;
        }
        if (outputDir == null || outputDir.isBlank()) {
            outputDir = "build/access-matrix";
        }
        specs = List.copyOf(specs == null ? List.of() : specs);
        roles = List.copyOf(roles == null ? List.of() : roles);
        expect = Map.copyOf(expect == null ? Map.of() : expect);
        Map<String, Map<String, String>> copied = new LinkedHashMap<>();
        if (operations != null) {
            operations.forEach((operationId, rules) -> copied.put(operationId, Map.copyOf(rules == null ? Map.of() : rules)));
        }
        operations = Map.copyOf(copied);
        if (failOnUndeclared == null) {
            failOnUndeclared = false;
        }
        validate(expect, operations);
    }

    /** The rule for one cell: the operation's own line first, then the role's line. */
    public Optional<AccessRule> rule(String operationId, String role) {
        String text = operations.getOrDefault(operationId, Map.of()).get(role);
        if (text == null) {
            text = expect.get(role);
        }
        return Optional.ofNullable(text).map(AccessRule::parse);
    }

    /** Explicit roles, else every role an expectation names, in a stable order. */
    public List<String> declaredRoles() {
        if (!roles.isEmpty()) {
            return roles;
        }
        Set<String> named = new LinkedHashSet<>(new java.util.TreeSet<>(expect.keySet()));
        operations.values().forEach(rules -> named.addAll(new java.util.TreeSet<>(rules.keySet())));
        return List.copyOf(named);
    }

    /** Every bad rule at once, so a typo fails the context with all of them, not the run one at a time. */
    private static void validate(Map<String, String> expect, Map<String, Map<String, String>> operations) {
        List<String> problems = new ArrayList<>();
        expect.forEach((role, text) -> check(problems, "expect." + role, text));
        operations.forEach((operationId, rules) ->
                rules.forEach((role, text) -> check(problems, "operations." + operationId + "." + role, text)));
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Invalid forge.access-matrix rules: " + String.join("; ", problems));
        }
    }

    private static void check(List<String> problems, String key, String text) {
        try {
            AccessRule.parse(text);
        } catch (IllegalArgumentException e) {
            problems.add(key + ": " + e.getMessage());
        }
    }
}
