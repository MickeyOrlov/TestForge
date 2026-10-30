package io.testforge.api.explorer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Puts producers before the operations their extracted values feed, and keeps
 * the original (deterministic) order everywhere else. A cycle between rules
 * cannot be satisfied; those operations keep their original order and the
 * rules involved simply report what they found.
 */
final class OperationOrder {

    private OperationOrder() {
    }

    static List<ExplorableOperation> forExtraction(List<ExplorableOperation> operations, List<ExtractionRule> rules) {
        if (rules.isEmpty()) {
            return operations;
        }
        List<ExplorableOperation> ordered = new ArrayList<>();
        Set<ExplorableOperation> remaining = new LinkedHashSet<>(operations);
        while (!remaining.isEmpty()) {
            ExplorableOperation next = remaining.stream()
                    .filter(candidate -> remaining.stream().noneMatch(other -> other != candidate
                            && produces(other, candidate, rules)))
                    .findFirst()
                    // only a cycle is left: fall back to the original order
                    .orElse(remaining.iterator().next());
            ordered.add(next);
            remaining.remove(next);
        }
        return List.copyOf(ordered);
    }

    private static boolean produces(ExplorableOperation producer, ExplorableOperation consumer,
                                    List<ExtractionRule> rules) {
        return rules.stream().anyMatch(rule -> rule.from().equals(producer.operationId())
                && rule.mayFeed(consumer.operationId()));
    }
}
