package io.testforge.api.discovery;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record CatalogDiff(
        boolean baselinePresent,
        Map<String, ApiEndpoint> added,
        Map<String, ApiEndpoint> removed,
        List<EndpointChange> changed) {

    public CatalogDiff {
        added = java.util.Collections.unmodifiableMap(added == null ? Map.of() : new TreeMap<>(added));
        removed = java.util.Collections.unmodifiableMap(removed == null ? Map.of() : new TreeMap<>(removed));
        changed = List.copyOf(changed == null ? List.of() : changed);
    }

    public static CatalogDiff noBaseline() {
        return new CatalogDiff(false, Map.of(), Map.of(), List.of());
    }

    public static CatalogDiff between(EndpointCatalog baseline, EndpointCatalog current) {
        Map<String, ApiEndpoint> baselineByKey = byKey(baseline);
        Map<String, ApiEndpoint> currentByKey = byKey(current);
        Map<String, ApiEndpoint> added = new TreeMap<>();
        Map<String, ApiEndpoint> removed = new TreeMap<>();
        List<EndpointChange> changed = new java.util.ArrayList<>();

        for (Map.Entry<String, ApiEndpoint> entry : currentByKey.entrySet()) {
            ApiEndpoint previous = baselineByKey.get(entry.getKey());
            if (previous == null) {
                added.put(entry.getKey(), entry.getValue());
            } else if (!previous.equals(entry.getValue())) {
                changed.add(new EndpointChange(entry.getKey(), previous, entry.getValue()));
            }
        }
        for (Map.Entry<String, ApiEndpoint> entry : baselineByKey.entrySet()) {
            if (!currentByKey.containsKey(entry.getKey())) {
                removed.put(entry.getKey(), entry.getValue());
            }
        }

        return new CatalogDiff(true, added, removed, changed);
    }

    public boolean empty() {
        return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
    }

    private static Map<String, ApiEndpoint> byKey(EndpointCatalog catalog) {
        Map<String, ApiEndpoint> endpoints = new TreeMap<>();
        for (ApiEndpoint endpoint : catalog.endpoints()) {
            endpoints.put(endpoint.key(), endpoint);
        }
        return endpoints;
    }

    public record EndpointChange(String key, ApiEndpoint baseline, ApiEndpoint current) {
    }
}
