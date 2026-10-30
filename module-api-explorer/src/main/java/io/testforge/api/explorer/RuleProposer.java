package io.testforge.api.explorer;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the document — never the environment — for likely producer/consumer
 * links: which callable {@code GET} returns a value one of another operation's
 * path parameters needs. Two signals, strongest first:
 *
 * <ol>
 *   <li><b>parent collection</b>: the producer's path is the consumer's path
 *       without its last {@code {parameter}} segment ({@code GET /tasks} for
 *       {@code GET /tasks/{taskId}}), and its response holds an {@code id} or a
 *       property named like the parameter;</li>
 *   <li><b>same name</b>: any parameterless {@code GET} whose response holds a
 *       property named exactly like the parameter.</li>
 * </ol>
 *
 * Parameters a human configured, or an existing rule already feeds, get no
 * proposal. Output is deterministic: operations in selection order, shortest
 * JSON path first.
 */
final class RuleProposer {

    private static final int MAX_DEPTH = 4;

    private RuleProposer() {
    }

    static List<ProposedRule> propose(List<ExplorableOperation> operations, ApiExplorerProperties properties) {
        List<ProposedRule> proposals = new ArrayList<>();
        for (ExplorableOperation consumer : operations) {
            for (Parameter parameter : consumer.parameters()) {
                if (!"path".equals(parameter.getIn()) || covered(consumer, parameter.getName(), properties)) {
                    continue;
                }
                best(consumer, parameter.getName(), operations).ifPresent(proposals::add);
            }
        }
        return List.copyOf(proposals);
    }

    private static boolean covered(ExplorableOperation consumer, String parameter, ApiExplorerProperties properties) {
        String configured = properties.parameters().find(consumer.operationId(), parameter);
        return (configured != null && !configured.isBlank())
                || properties.extract().stream().anyMatch(rule -> rule.feeds(consumer.operationId(), parameter));
    }

    private static Optional<ProposedRule> best(ExplorableOperation consumer, String parameter,
                                               List<ExplorableOperation> operations) {
        String into = consumer.operationId() + "." + parameter;
        String parent = parentPath(consumer.pathTemplate(), parameter);

        ProposedRule byName = null;
        for (ExplorableOperation producer : operations) {
            if (producer == consumer || !"GET".equals(producer.method()) || hasPathParameters(producer)) {
                continue;
            }
            Schema<?> response = jsonResponse(producer.operation());
            if (response == null) {
                continue;
            }
            if (producer.pathTemplate().equals(parent)) {
                Optional<String> path = find(response, parameter).or(() -> find(response, "id"));
                if (path.isPresent()) {
                    return Optional.of(new ProposedRule(producer.operationId(), path.get(), into,
                            "%s lists the items %s reads".formatted(producer.key(), consumer.key())));
                }
            }
            if (byName == null) {
                Optional<String> path = find(response, parameter);
                if (path.isPresent()) {
                    byName = new ProposedRule(producer.operationId(), path.get(), into,
                            "%s returns a property named %s".formatted(producer.key(), parameter));
                }
            }
        }
        return Optional.ofNullable(byName);
    }

    /** {@code /tasks/{taskId}} → {@code /tasks}; null unless the parameter is the last segment. */
    private static String parentPath(String path, String parameter) {
        String suffix = "/{" + parameter + "}";
        return path.endsWith(suffix) ? path.substring(0, path.length() - suffix.length()) : null;
    }

    private static boolean hasPathParameters(ExplorableOperation operation) {
        return operation.parameters().stream().anyMatch(parameter -> "path".equals(parameter.getIn()));
    }

    private static Schema<?> jsonResponse(Operation operation) {
        if (operation.getResponses() == null) {
            return null;
        }
        for (Map.Entry<String, ApiResponse> entry : new java.util.TreeMap<>(operation.getResponses()).entrySet()) {
            if (!entry.getKey().startsWith("2") || entry.getValue() == null) {
                continue;
            }
            Content content = entry.getValue().getContent();
            if (content == null) {
                continue;
            }
            for (Map.Entry<String, MediaType> media : content.entrySet()) {
                if (media.getKey().toLowerCase(Locale.ROOT).contains("json") && media.getValue() != null
                        && media.getValue().getSchema() != null) {
                    return media.getValue().getSchema();
                }
            }
        }
        return null;
    }

    /** Breadth-first, so the shortest path to a matching scalar property wins. */
    private static Optional<String> find(Schema<?> root, String property) {
        record Step(Schema<?> schema, String path, int depth) {
        }
        Deque<Step> queue = new ArrayDeque<>();
        queue.add(new Step(root, "$", 0));
        while (!queue.isEmpty()) {
            Step step = queue.poll();
            Schema<?> schema = step.schema();
            if (schema == null || step.depth() > MAX_DEPTH) {
                continue;
            }
            if ("array".equals(Schemas.type(schema)) || schema.getItems() != null) {
                queue.add(new Step(schema.getItems(), step.path() + "[0]", step.depth() + 1));
                continue;
            }
            Map<String, Schema> properties = schema.getProperties();
            if (properties == null) {
                continue;
            }
            for (Map.Entry<String, Schema> entry : new java.util.TreeMap<>(properties).entrySet()) {
                String type = Schemas.type(entry.getValue());
                boolean scalar = type != null && !"object".equals(type) && !"array".equals(type);
                if (scalar && entry.getKey().equalsIgnoreCase(property)) {
                    return Optional.of(join(step.path(), entry.getKey()));
                }
            }
            for (Map.Entry<String, Schema> entry : new java.util.TreeMap<>(properties).entrySet()) {
                queue.add(new Step(entry.getValue(), join(step.path(), entry.getKey()), step.depth() + 1));
            }
        }
        return Optional.empty();
    }

    private static String join(String path, String property) {
        return path + "." + property;
    }
}
