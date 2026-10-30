package io.testforge.core.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

/**
 * Minimal dot-path reader over Jackson trees, shared by modules that match
 * fields inside JSON payloads (module-contract rules, module-kafka filters).
 *
 * <p>Supports {@code $.a.b}, {@code a.b}, JSON Pointer ({@code /a/b}) and
 * array indices ({@code items[0].sku}, {@code $[0].id} on a root array). Anything fancier — filters,
 * wildcards, recursive descent — is out of scope by design; use a full
 * JsonPath library at that point.
 */
public final class JsonPath {

    private JsonPath() {
    }

    public static JsonNode read(JsonNode root, String path) {
        if (root == null) {
            return MissingNode.getInstance();
        }
        if (path.startsWith("/")) {
            return root.at(path);
        }

        String normalized = path;
        if (normalized.startsWith("$.")) {
            normalized = normalized.substring(2);
        } else if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        } else if (normalized.equals("$")) {
            return root;
        } else if (normalized.startsWith("$[")) {
            // a root array: "$[0].id" reads index 0 of the document itself
            normalized = normalized.substring(1);
        }

        JsonNode current = root;
        for (String segment : normalized.split("\\.")) {
            if (segment.isBlank()) {
                continue;
            }
            current = readSegment(current, segment);
            if (current.isMissingNode()) {
                return current;
            }
        }
        return current;
    }

    private static JsonNode readSegment(JsonNode current, String segment) {
        int bracket = segment.indexOf('[');
        String field = bracket >= 0 ? segment.substring(0, bracket) : segment;
        if (!field.isBlank()) {
            current = current.path(field);
        }

        while (bracket >= 0) {
            int close = segment.indexOf(']', bracket);
            if (close < 0) {
                return MissingNode.getInstance();
            }
            String indexText = segment.substring(bracket + 1, close);
            try {
                current = current.path(Integer.parseInt(indexText));
            } catch (NumberFormatException e) {
                return MissingNode.getInstance();
            }
            bracket = segment.indexOf('[', close);
        }

        return current;
    }
}
