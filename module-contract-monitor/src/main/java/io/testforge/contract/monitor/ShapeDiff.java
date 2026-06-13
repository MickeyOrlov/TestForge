package io.testforge.contract.monitor;

import java.util.List;
import java.util.Map;

public record ShapeDiff(
        boolean baselinePresent,
        Map<String, String> added,
        Map<String, String> removed,
        List<TypeChange> changed) {

    public ShapeDiff {
        added = Map.copyOf(added == null ? Map.of() : added);
        removed = Map.copyOf(removed == null ? Map.of() : removed);
        changed = List.copyOf(changed == null ? List.of() : changed);
    }

    public static ShapeDiff noBaseline() {
        return new ShapeDiff(false, Map.of(), Map.of(), List.of());
    }

    public static ShapeDiff between(Map<String, String> baseline, Map<String, String> current) {
        java.util.Map<String, String> added = new java.util.TreeMap<>();
        java.util.Map<String, String> removed = new java.util.TreeMap<>();
        java.util.List<TypeChange> changed = new java.util.ArrayList<>();

        for (Map.Entry<String, String> entry : current.entrySet()) {
            String previous = baseline.get(entry.getKey());
            if (previous == null) {
                added.put(entry.getKey(), entry.getValue());
            } else if (!previous.equals(entry.getValue())) {
                changed.add(new TypeChange(entry.getKey(), previous, entry.getValue()));
            }
        }
        for (Map.Entry<String, String> entry : baseline.entrySet()) {
            if (!current.containsKey(entry.getKey())) {
                removed.put(entry.getKey(), entry.getValue());
            }
        }

        return new ShapeDiff(true, added, removed, changed);
    }

    public boolean empty() {
        return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
    }

    public record TypeChange(String path, String baselineType, String currentType) {
    }
}
