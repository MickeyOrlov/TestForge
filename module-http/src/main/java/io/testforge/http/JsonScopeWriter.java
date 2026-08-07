package io.testforge.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;

/**
 * Writes the scenario scope id into a JSON request body at the same dot path
 * {@code module-mock} matches on.
 *
 * <p>The write side deliberately covers less than {@code JsonPath} reads:
 * object segments only, no array indices. Missing intermediate objects are
 * created; an existing value at the target path is overwritten.
 */
final class JsonScopeWriter {

    private final ObjectMapper mapper;

    JsonScopeWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Returns the body with {@code value} written at {@code path}, or an empty
     * optional when the body is not a JSON object — a form post, a plain
     * string, an array — and there is nothing to correlate.
     */
    Optional<String> write(String body, String path, String value) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }

        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            return Optional.empty();
        }
        if (!root.isObject()) {
            return Optional.empty();
        }

        String[] segments = segments(path);
        ObjectNode current = (ObjectNode) root;
        for (int i = 0; i < segments.length - 1; i++) {
            JsonNode child = current.get(segments[i]);
            if (child == null || child.isNull()) {
                current = current.putObject(segments[i]);
            } else if (child.isObject()) {
                current = (ObjectNode) child;
            } else {
                throw new IllegalStateException(
                        "Cannot write the test scope at '%s': segment '%s' is already a %s in the request body"
                                .formatted(path, segments[i], child.getNodeType()));
            }
        }
        current.put(segments[segments.length - 1], value);

        try {
            return Optional.of(mapper.writeValueAsString(root));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to re-serialize the request body after writing the test scope", e);
        }
    }

    private static String[] segments(String path) {
        String normalized = path;
        if (normalized.startsWith("$.")) {
            normalized = normalized.substring(2);
        } else if (normalized.startsWith("/")) {
            normalized = normalized.substring(1).replace('/', '.');
        } else if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }

        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Scope path '%s' does not point at a field".formatted(path));
        }
        if (normalized.indexOf('[') >= 0) {
            throw new IllegalArgumentException(
                    "Scope path '%s' contains an array index; scope injection supports object paths only"
                            .formatted(path));
        }
        return normalized.split("\\.");
    }
}
