package io.testforge.api.discovery;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public class EndpointCatalogBuilder {

    public EndpointCatalog build(String specId, OpenAPI openApi) {
        List<ApiEndpoint> endpoints = new java.util.ArrayList<>();
        if (openApi.getPaths() == null) {
            return new EndpointCatalog(specId, List.of());
        }

        for (Map.Entry<String, PathItem> pathEntry : openApi.getPaths().entrySet()) {
            PathItem pathItem = pathEntry.getValue();
            if (pathItem == null) {
                continue;
            }
            pathItem.readOperationsMap().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(operation -> endpoint(pathEntry.getKey(), operation.getKey(), operation.getValue()))
                    .forEach(endpoints::add);
        }

        endpoints.sort(java.util.Comparator.comparing(ApiEndpoint::key));
        return new EndpointCatalog(specId, endpoints);
    }

    private ApiEndpoint endpoint(String path, PathItem.HttpMethod method, Operation operation) {
        String httpMethod = method.name().toUpperCase(Locale.ROOT);
        String key = httpMethod + " " + path;
        return new ApiEndpoint(
                key,
                httpMethod,
                path,
                operationId(httpMethod, path, operation),
                sorted(operation.getTags()),
                requestContentTypes(operation),
                responses(operation),
                Boolean.TRUE.equals(operation.getDeprecated()));
    }

    private String operationId(String method, String path, Operation operation) {
        if (operation.getOperationId() != null && !operation.getOperationId().isBlank()) {
            return operation.getOperationId();
        }
        return method.toLowerCase(Locale.ROOT) + " " + path;
    }

    private List<String> requestContentTypes(Operation operation) {
        if (operation.getRequestBody() == null || operation.getRequestBody().getContent() == null) {
            return List.of();
        }
        return sorted(operation.getRequestBody().getContent().keySet());
    }

    private Map<String, List<String>> responses(Operation operation) {
        Map<String, List<String>> responses = new TreeMap<>();
        if (operation.getResponses() == null) {
            return responses;
        }
        for (Map.Entry<String, ApiResponse> response : operation.getResponses().entrySet()) {
            Content content = response.getValue() == null ? null : response.getValue().getContent();
            responses.put(response.getKey(), content == null ? List.of() : sorted(content.keySet()));
        }
        return responses;
    }

    private List<String> sorted(java.util.Collection<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .sorted()
                .toList();
    }
}
