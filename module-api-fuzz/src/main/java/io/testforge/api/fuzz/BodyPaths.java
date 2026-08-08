package io.testforge.api.fuzz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The JSON path dialect body cases are addressed by: {@code $},
 * {@code $.profile.age}, {@code $.tags[0]}.
 *
 * <p>Concrete indices on purpose. A case mutates one element, and a report that
 * says {@code $.tags[0]} tells a reader exactly which one; the collapsed
 * {@code $.tags[]} used for shape snapshots elsewhere would not.
 */
final class BodyPaths {

    private BodyPaths() {
    }

    static String child(String parent, String field) {
        if (field.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return parent + "." + field;
        }
        return parent + "['" + field.replace("\\", "\\\\").replace("'", "\\'") + "']";
    }

    static String element(String parent, int index) {
        return parent + "[" + index + "]";
    }

    /** The node a path points at, or empty when the document has no such place. */
    static Optional<JsonNode> resolve(JsonNode root, String path) {
        JsonNode current = root;
        for (Step step : steps(path)) {
            if (current == null) {
                return Optional.empty();
            }
            current = step.index() == null ? current.get(step.field()) : current.get(step.index());
        }
        return Optional.ofNullable(current);
    }

    /** Replaces the value at {@code path}; returns false when the path does not exist. */
    static boolean set(JsonNode root, String path, JsonNode value) {
        return withParent(root, path, (parent, step) -> {
            if (step.index() == null && parent instanceof ObjectNode object) {
                object.set(step.field(), value);
                return true;
            }
            if (step.index() != null && parent instanceof ArrayNode array && step.index() < array.size()) {
                array.set(step.index(), value);
                return true;
            }
            return false;
        });
    }

    /** Removes the value at {@code path}; returns false when the path does not exist. */
    static boolean remove(JsonNode root, String path) {
        return withParent(root, path, (parent, step) -> {
            if (step.index() == null && parent instanceof ObjectNode object) {
                return object.remove(step.field()) != null;
            }
            if (step.index() != null && parent instanceof ArrayNode array && step.index() < array.size()) {
                array.remove(step.index());
                return true;
            }
            return false;
        });
    }

    private static boolean withParent(JsonNode root, String path, ParentOperation operation) {
        List<Step> steps = steps(path);
        if (steps.isEmpty()) {
            return false;
        }

        JsonNode parent = root;
        for (int index = 0; index < steps.size() - 1; index++) {
            Step step = steps.get(index);
            parent = step.index() == null ? parent.get(step.field()) : parent.get(step.index());
            if (parent == null) {
                return false;
            }
        }
        return operation.apply(parent, steps.getLast());
    }

    private static List<Step> steps(String path) {
        List<Step> steps = new ArrayList<>();
        String normalized = path.startsWith("$") ? path.substring(1) : path;

        int cursor = 0;
        while (cursor < normalized.length()) {
            char current = normalized.charAt(cursor);
            if (current == '.') {
                int next = nextDelimiter(normalized, cursor + 1);
                steps.add(new Step(normalized.substring(cursor + 1, next), null));
                cursor = next;
            } else if (current == '[') {
                int close = normalized.indexOf(']', cursor);
                if (close < 0) {
                    return steps;
                }
                String inside = normalized.substring(cursor + 1, close);
                if (inside.startsWith("'")) {
                    steps.add(new Step(inside.substring(1, inside.length() - 1), null));
                } else {
                    steps.add(new Step(null, Integer.parseInt(inside)));
                }
                cursor = close + 1;
            } else {
                cursor++;
            }
        }
        return steps;
    }

    private static int nextDelimiter(String path, int from) {
        for (int index = from; index < path.length(); index++) {
            char current = path.charAt(index);
            if (current == '.' || current == '[') {
                return index;
            }
        }
        return path.length();
    }

    private record Step(String field, Integer index) {
    }

    @FunctionalInterface
    private interface ParentOperation {
        boolean apply(JsonNode parent, Step step);
    }
}
