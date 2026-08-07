package io.testforge.api.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import io.testforge.api.discovery.EndpointDescriptor.ParameterDescriptor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns the {@code paths} object into a flat, deterministically ordered list of
 * endpoints.
 *
 * <p>Two things the OpenAPI specification allows that have to be normalized
 * here: parameters may be declared on the path item and inherited by every
 * operation under it, and any parameter may be a {@code $ref} into
 * {@code components.parameters}.
 */
public class EndpointCatalogBuilder {

    /** Path-item keys that are operations; anything else is metadata. */
    private static final List<String> HTTP_METHODS =
            List.of("get", "head", "options", "post", "put", "patch", "delete", "trace");

    public EndpointCatalog build(OpenApiDocument document, String baseUrl) {
        List<EndpointDescriptor> endpoints = new ArrayList<>();
        Map<String, Integer> usedNames = new HashMap<>();

        // TreeMap: the catalog must not reorder between runs, or every diff of
        // the artifact becomes unreadable
        Map<String, JsonNode> paths = new TreeMap<>();
        document.paths().properties().forEach(entry -> paths.put(entry.getKey(), entry.getValue()));

        paths.forEach((path, pathItem) -> {
            List<ParameterDescriptor> shared = parameters(document, pathItem.path("parameters"));

            for (String method : HTTP_METHODS) {
                JsonNode operation = pathItem.path(method);
                if (!operation.isObject()) {
                    continue;
                }
                endpoints.add(describe(document, path, method, operation, shared, usedNames));
            }
        });

        return new EndpointCatalog(
                document.openapi(),
                document.title(),
                document.version(),
                document.source(),
                baseUrl,
                endpoints);
    }

    private EndpointDescriptor describe(
            OpenApiDocument document,
            String path,
            String method,
            JsonNode operation,
            List<ParameterDescriptor> shared,
            Map<String, Integer> usedNames) {

        String operationId = operation.path("operationId").asText(null);
        String upperMethod = method.toUpperCase(Locale.ROOT);

        return new EndpointDescriptor(
                artifactName(operationId, upperMethod, path, usedNames),
                upperMethod,
                path,
                operationId,
                operation.path("summary").asText(null),
                tags(operation),
                operation.path("deprecated").asBoolean(false),
                merge(shared, parameters(document, operation.path("parameters"))),
                operation);
    }

    private List<String> tags(JsonNode operation) {
        List<String> tags = new ArrayList<>();
        operation.path("tags").forEach(tag -> tags.add(tag.asText()));
        return tags;
    }

    private List<ParameterDescriptor> parameters(OpenApiDocument document, JsonNode node) {
        List<ParameterDescriptor> parameters = new ArrayList<>();
        if (!node.isArray()) {
            return parameters;
        }

        node.forEach(entry -> {
            JsonNode parameter = document.dereference(entry);
            String name = parameter.path("name").asText(null);
            String in = parameter.path("in").asText(null);
            if (name == null || in == null) {
                return;
            }
            // the specification makes path parameters mandatory regardless of
            // what the document says
            boolean required = "path".equals(in) || parameter.path("required").asBoolean(false);
            parameters.add(new ParameterDescriptor(name, in, required, parameter));
        });
        return parameters;
    }

    /** Operation-level parameters override inherited ones with the same name and location. */
    private List<ParameterDescriptor> merge(List<ParameterDescriptor> shared, List<ParameterDescriptor> own) {
        Map<String, ParameterDescriptor> merged = new LinkedHashMap<>();
        shared.forEach(parameter -> merged.put(key(parameter), parameter));
        own.forEach(parameter -> merged.put(key(parameter), parameter));
        return List.copyOf(merged.values());
    }

    private String key(ParameterDescriptor parameter) {
        return parameter.in() + ":" + parameter.name();
    }

    /**
     * File-name stem for this endpoint's artifacts. Two different paths can
     * sanitize to the same stem ({@code /a-b} and {@code /a/b}), so collisions
     * get a deterministic suffix and the resolved name is recorded in the
     * catalog.
     */
    private String artifactName(String operationId, String method, String path, Map<String, Integer> usedNames) {
        String base = ArtifactWriter.safeFileName(
                operationId != null && !operationId.isBlank() ? operationId : method + "-" + path);

        int seen = usedNames.merge(base, 1, Integer::sum);
        return seen == 1 ? base : base + "-" + seen;
    }
}
