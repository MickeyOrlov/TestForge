package io.testforge.api.discovery;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record ApiShapeDiff(
        boolean baselinePresent,
        Map<String, SchemaShapeEntry> added,
        Map<String, SchemaShapeEntry> removed,
        List<FieldChange> changed) {

    public ApiShapeDiff {
        added = java.util.Collections.unmodifiableMap(added == null ? Map.of() : new TreeMap<>(added));
        removed = java.util.Collections.unmodifiableMap(removed == null ? Map.of() : new TreeMap<>(removed));
        changed = List.copyOf(changed == null ? List.of() : changed);
    }

    public static ApiShapeDiff noBaseline() {
        return new ApiShapeDiff(false, Map.of(), Map.of(), List.of());
    }

    public static ApiShapeDiff between(ApiSchemaShape baseline, ApiSchemaShape current) {
        Map<String, SchemaShapeEntry> added = new TreeMap<>();
        Map<String, SchemaShapeEntry> removed = new TreeMap<>();
        List<FieldChange> changed = new java.util.ArrayList<>();

        for (Map.Entry<String, SchemaShapeEntry> entry : current.fields().entrySet()) {
            SchemaShapeEntry previous = baseline.fields().get(entry.getKey());
            if (previous == null) {
                added.put(entry.getKey(), entry.getValue());
            } else if (!previous.equals(entry.getValue())) {
                changed.add(new FieldChange(entry.getKey(), previous, entry.getValue()));
            }
        }
        for (Map.Entry<String, SchemaShapeEntry> entry : baseline.fields().entrySet()) {
            if (!current.fields().containsKey(entry.getKey())) {
                removed.put(entry.getKey(), entry.getValue());
            }
        }

        return new ApiShapeDiff(true, added, removed, changed);
    }

    public boolean empty() {
        return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
    }

    public record FieldChange(String path, SchemaShapeEntry baseline, SchemaShapeEntry current) {
    }
}
