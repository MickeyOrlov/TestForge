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

    /**
     * Each difference judged for a consumer of the baseline: a path no sampled
     * message has any more, or a type it may now arrive as, breaks it; a new
     * path or a narrowed type does not. Worst first.
     */
    public List<io.testforge.contract.json.ContractChange> impact() {
        List<io.testforge.contract.json.ContractChange> changes = new java.util.ArrayList<>();
        removed.forEach((path, type) -> changes.add(new io.testforge.contract.json.ContractChange(path,
                io.testforge.contract.json.ContractChange.Impact.BREAKING, "no sampled message has it any more")));
        added.forEach((path, type) -> changes.add(new io.testforge.contract.json.ContractChange(path,
                io.testforge.contract.json.ContractChange.Impact.SAFE, "new " + type)));
        for (TypeChange change : changed) {
            java.util.Set<String> was = java.util.Set.of(change.baselineType().split("\\|"));
            java.util.Set<String> now = new java.util.TreeSet<>(java.util.List.of(change.currentType().split("\\|")));
            now.removeAll(was);
            changes.add(now.isEmpty()
                    ? new io.testforge.contract.json.ContractChange(change.path(),
                            io.testforge.contract.json.ContractChange.Impact.SAFE,
                            "narrowed " + change.baselineType() + " → " + change.currentType())
                    : new io.testforge.contract.json.ContractChange(change.path(),
                            io.testforge.contract.json.ContractChange.Impact.BREAKING,
                            "may now arrive as " + now + " (was " + change.baselineType() + ")"));
        }
        changes.sort(java.util.Comparator.comparing(io.testforge.contract.json.ContractChange::impact)
                .thenComparing(io.testforge.contract.json.ContractChange::path));
        return List.copyOf(changes);
    }

    public boolean breaking() {
        return impact().stream().anyMatch(change ->
                change.impact() == io.testforge.contract.json.ContractChange.Impact.BREAKING);
    }

    public record TypeChange(String path, String baselineType, String currentType) {
    }
}
