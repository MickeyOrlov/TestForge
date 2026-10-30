package io.testforge.api.explorer;

import java.util.List;

/**
 * Takes one value from a response and feeds it to later requests: the
 * declarative, heuristic-free start of stateful exploration.
 *
 * <pre>
 * forge:
 *   api-explorer:
 *     extract:
 *       - from: listOrders          # operationId whose 2xx response is read
 *         json-path: "$[0].id"
 *         into: [getOrder.id, cancelOrder.id]   # operationId.parameter
 *       - from: currentUser
 *         json-path: "$.tenantId"
 *         into: [tenantId]          # that parameter name on any operation
 * </pre>
 *
 * @param from     operationId of the producer
 * @param jsonPath where the value sits in the producer's JSON response
 * @param into     consumers: {@code operationId.parameter}, or a bare parameter name for every operation
 */
public record ExtractionRule(String from, String jsonPath, List<String> into) {

    public ExtractionRule {
        if (from == null || from.isBlank()) {
            throw new IllegalArgumentException("forge.api-explorer.extract entries need 'from' (an operationId)");
        }
        if (jsonPath == null || jsonPath.isBlank()) {
            throw new IllegalArgumentException("forge.api-explorer.extract '%s' needs 'json-path'".formatted(from));
        }
        into = List.copyOf(into == null ? List.of() : into);
        if (into.isEmpty()) {
            throw new IllegalArgumentException("forge.api-explorer.extract '%s' needs at least one 'into' target"
                    .formatted(from));
        }
    }

    /** True when this rule feeds {@code parameter} of {@code operationId}. */
    boolean feeds(String operationId, String parameter) {
        for (String target : into) {
            int dot = target.indexOf('.');
            if (dot < 0 ? target.equals(parameter)
                    : target.substring(0, dot).equals(operationId) && target.substring(dot + 1).equals(parameter)) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code operationId} may consume this rule's value (named, or any operation for a bare name). */
    boolean mayFeed(String operationId) {
        return !operationId.equals(from) && into.stream().anyMatch(target -> {
            int dot = target.indexOf('.');
            return dot < 0 || target.substring(0, dot).equals(operationId);
        });
    }
}
