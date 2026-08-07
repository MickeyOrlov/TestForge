package io.testforge.api.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

/**
 * A parsed OpenAPI 3.x document, kept as a Jackson tree.
 *
 * <p>The module reads the document rather than modelling it: only
 * {@code paths}, operation metadata, parameters and {@code components} are
 * needed, and a full object model would mean a dependency an order of
 * magnitude larger than the ~250 lines this costs.
 */
public record OpenApiDocument(
        String openapi,
        String title,
        String version,
        String source,
        JsonNode root) {

    /** True for OpenAPI 3.1.x, whose schemas are JSON Schema 2020-12. */
    public boolean oas31() {
        return openapi != null && openapi.startsWith("3.1");
    }

    public JsonNode paths() {
        return root.path("paths");
    }

    public JsonNode components() {
        return root.path("components");
    }

    /**
     * Resolves a local {@code #/...} reference. Remote and file references are
     * out of scope by design and resolve to a missing node, which callers
     * report as unresolvable rather than treating as a failure.
     */
    public JsonNode resolve(String ref) {
        if (ref == null || !ref.startsWith("#/")) {
            return MissingNode.getInstance();
        }
        return root.at(ref.substring(1));
    }

    /** Follows {@code $ref} until a concrete node is reached. */
    public JsonNode dereference(JsonNode node) {
        JsonNode current = node;
        for (int hops = 0; hops < 20 && current.hasNonNull("$ref"); hops++) {
            current = resolve(current.get("$ref").asText());
        }
        return current;
    }
}
