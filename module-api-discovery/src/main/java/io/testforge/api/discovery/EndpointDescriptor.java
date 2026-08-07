package io.testforge.api.discovery;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * One operation from the OpenAPI document.
 *
 * <p>{@code path} is always the templated form ({@code /orders/{id}}), never a
 * resolved URL — the catalog and every artifact key off it, so a resolved
 * identifier cannot leak into a file that gets committed or uploaded.
 *
 * <p>The raw operation node rides along for the prober and the drift checker
 * but is excluded from serialization: the catalog is a review artifact, not a
 * copy of the spec.
 */
public record EndpointDescriptor(
        String artifactName,
        String method,
        String path,
        String operationId,
        String summary,
        List<String> tags,
        boolean deprecated,
        List<ParameterDescriptor> parameters,
        @JsonIgnore JsonNode operation) {

    public EndpointDescriptor {
        tags = List.copyOf(tags == null ? List.of() : tags);
        parameters = List.copyOf(parameters == null ? List.of() : parameters);
    }

    /** {@code GET /orders/{id}} — how the endpoint appears in reports and logs. */
    public String label() {
        return method + " " + path;
    }

    public List<ParameterDescriptor> pathParameters() {
        return parameters.stream().filter(parameter -> "path".equals(parameter.in())).toList();
    }

    public List<ParameterDescriptor> requiredQueryParameters() {
        return parameters.stream()
                .filter(parameter -> "query".equals(parameter.in()) && parameter.required())
                .toList();
    }

    /**
     * One parameter of the operation. Path parameters are implicitly required
     * by the specification; the flag is normalized at build time.
     */
    public record ParameterDescriptor(
            String name,
            String in,
            boolean required,
            @JsonIgnore JsonNode node) {
    }
}
