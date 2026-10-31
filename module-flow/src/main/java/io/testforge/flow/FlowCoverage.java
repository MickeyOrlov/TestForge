package io.testforge.flow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which declared transitions the suite took, per flow name, across every run
 * of every runner of that name. A branch no test ever took — the manager
 * review nobody's amount was high enough for — is a gap the green build hides.
 *
 * <p>Only declared transitions count as gaps ({@link FlowStep#declaredNext()});
 * a flow whose steps declare nothing has none to report.
 */
public class FlowCoverage {

    /** One flow's coverage; transitions are written {@code FROM -> TO}. */
    public record Entry(String flow, Set<String> declared, Set<String> taken, Set<String> uncovered, long runs) {
    }

    private final Map<String, Set<String>> declared = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> taken = new ConcurrentHashMap<>();
    private final Map<String, Long> runs = new ConcurrentHashMap<>();

    <S> void declare(String flow, Iterable<? extends FlowStep<S>> steps) {
        Set<String> edges = declared.computeIfAbsent(flow, ignored -> ConcurrentHashMap.newKeySet());
        for (FlowStep<S> step : steps) {
            step.declaredNext().forEach(next -> edges.add(edge(step.state(), next)));
        }
    }

    void run(String flow) {
        runs.merge(flow, 1L, Long::sum);
    }

    void took(String flow, Object from, Object to) {
        taken.computeIfAbsent(flow, ignored -> ConcurrentHashMap.newKeySet()).add(edge(from, to));
    }

    /** Every flow that ran, by name. */
    public List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        new TreeMap<>(runs).forEach((flow, count) -> {
            Set<String> declaredEdges = new TreeSet<>(declared.getOrDefault(flow, Set.of()));
            Set<String> takenEdges = new TreeSet<>(taken.getOrDefault(flow, Set.of()));
            Set<String> uncovered = new TreeSet<>(declaredEdges);
            uncovered.removeAll(takenEdges);
            entries.add(new Entry(flow, declaredEdges, takenEdges, uncovered, count));
        });
        return entries;
    }

    public boolean isEmpty() {
        return runs.isEmpty();
    }

    String render() {
        StringBuilder out = new StringBuilder("# Flow transition coverage\n\n");
        for (Entry entry : entries()) {
            out.append("## ").append(entry.flow()).append("\n\n")
                    .append("- runs: ").append(entry.runs()).append('\n');
            if (entry.declared().isEmpty()) {
                out.append("- no declared transitions: steps declare nothing (`FlowStep.of(..., next...)`)\n\n");
                continue;
            }
            out.append("- declared transitions taken: ")
                    .append(entry.declared().size() - entry.uncovered().size()).append(" of ")
                    .append(entry.declared().size()).append('\n');
            if (!entry.uncovered().isEmpty()) {
                out.append("- never taken by any test:\n");
                entry.uncovered().forEach(edge -> out.append("  - `").append(edge).append("`\n"));
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static String edge(Object from, Object to) {
        return from + " -> " + to;
    }
}
