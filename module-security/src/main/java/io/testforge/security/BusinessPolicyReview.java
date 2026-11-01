package io.testforge.security;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Structural metadata only; no payload literals, observed values or value hashes. */
public record BusinessPolicyReview(
        int formatVersion,
        String suite,
        String policyVersion,
        List<String> inventory,
        Map<String, JsonNode> operations,
        JsonNode scope,
        Map<String, JsonNode> cases,
        Map<String, List<String>> dependencies) {
    public BusinessPolicyReview {
        suite = BusinessSecuritySuite.identifier(suite);
        policyVersion = BusinessSecuritySuite.identifier(policyVersion);
        scope = java.util.Objects.requireNonNull(scope, "policy scope is required");
        inventory = List.copyOf(inventory);
        operations = java.util.Collections.unmodifiableMap(new TreeMap<>(operations));
        cases = java.util.Collections.unmodifiableMap(new TreeMap<>(cases));
        var copied = new TreeMap<String, List<String>>();
        dependencies.forEach((key, values) -> copied.put(key, List.copyOf(values)));
        dependencies = java.util.Collections.unmodifiableMap(copied);
    }

    @Override
    public String toString() {
        return "BusinessPolicyReview[" + suite + "," + policyVersion + "]";
    }
}
