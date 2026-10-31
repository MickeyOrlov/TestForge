package io.testforge.api.fuzz;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * One failing check on one operation, however many generated cases tripped
 * it. Schemathesis reports every failing case; fifty cases breaking the same
 * check are one thing to fix, not fifty.
 *
 * @param key {@code <check> <operation>}, see {@link ApiFuzzFinding#key()}
 * @param occurrences how many failing cases share the key
 * @param phases the phases that found it ({@code Coverage}, {@code Fuzzing} ...)
 * @param methods the methods the failing cases sent; more than the operation's
 *        own when coverage probes an unexpected method
 * @param firstMessage the first failure message seen, as Schemathesis wrote it
 */
public record ApiFuzzFindingGroup(
        String key,
        String specId,
        String operationLabel,
        String checkName,
        int occurrences,
        Set<String> phases,
        Set<String> methods,
        String firstMessage) {

    public ApiFuzzFindingGroup {
        // sorted, so the report reads the same on every run
        phases = Collections.unmodifiableSortedSet(new TreeSet<>(phases == null ? Set.of() : phases));
        methods = Collections.unmodifiableSortedSet(new TreeSet<>(methods == null ? Set.of() : methods));
    }

    /** Groups findings by spec and key; the most frequent first, then by key. */
    public static List<ApiFuzzFindingGroup> of(List<ApiFuzzFinding> findings) {
        Map<String, List<ApiFuzzFinding>> byKey = new LinkedHashMap<>();
        for (ApiFuzzFinding finding : findings == null ? List.<ApiFuzzFinding>of() : findings) {
            byKey.computeIfAbsent(finding.specId() + "\n" + finding.key(), ignored -> new ArrayList<>()).add(finding);
        }
        List<ApiFuzzFindingGroup> groups = new ArrayList<>();
        byKey.values().forEach(same -> {
            ApiFuzzFinding first = same.getFirst();
            Set<String> phases = new TreeSet<>();
            Set<String> methods = new TreeSet<>();
            String message = null;
            for (ApiFuzzFinding finding : same) {
                if (finding.phase() != null) {
                    phases.add(finding.phase());
                }
                if (finding.method() != null) {
                    methods.add(finding.method());
                }
                if (message == null) {
                    message = finding.message();
                }
            }
            groups.add(new ApiFuzzFindingGroup(first.key(), first.specId(), first.operationLabel(),
                    first.checkName(), same.size(), phases, methods, message));
        });
        groups.sort(Comparator.comparing(ApiFuzzFindingGroup::occurrences).reversed()
                .thenComparing(group -> String.valueOf(group.specId()))
                .thenComparing(ApiFuzzFindingGroup::key));
        return List.copyOf(groups);
    }

    @Override
    public String toString() {
        return key + " (x" + occurrences + ")";
    }
}
