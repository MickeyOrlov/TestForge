package io.testforge.api.explorer;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Flattens a parsed document into the operations an exploration run will
 * consider, in a stable order.
 *
 * <p>Order matters beyond tidiness: artifact names, the operation budget and
 * the report all key off it, and a run whose output reshuffles between
 * identical inputs cannot be diffed.
 */
public class OperationSelector {

    public List<ExplorableOperation> select(String specId, OpenAPI openApi) {
        List<ExplorableOperation> operations = new ArrayList<>();
        if (openApi == null || openApi.getPaths() == null) {
            return List.of();
        }

        openApi.getPaths().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    PathItem pathItem = entry.getValue();
                    if (pathItem == null) {
                        return;
                    }
                    pathItem.readOperationsMap().entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .forEach(operation -> operations.add(describe(
                                    specId, entry.getKey(), operation.getKey(), operation.getValue(), pathItem)));
                });

        // sorted by "METHOD path" to match EndpointCatalogBuilder, so the same
        // API reads in the same order whichever module reports it
        return operations.stream()
                .sorted(Comparator.comparing(ExplorableOperation::key))
                .toList();
    }

    private ExplorableOperation describe(
            String specId, String path, PathItem.HttpMethod method, Operation operation, PathItem pathItem) {

        String httpMethod = method.name().toUpperCase(Locale.ROOT);
        return new ExplorableOperation(
                specId,
                operationId(httpMethod, path, operation),
                httpMethod,
                path,
                Boolean.TRUE.equals(operation.getDeprecated()),
                mergeParameters(pathItem.getParameters(), operation.getParameters()),
                operation);
    }

    /** Matches {@code EndpointCatalogBuilder} so both modules name the same operation identically. */
    private String operationId(String method, String path, Operation operation) {
        if (operation.getOperationId() != null && !operation.getOperationId().isBlank()) {
            return operation.getOperationId();
        }
        return method.toLowerCase(Locale.ROOT) + " " + path;
    }

    /** Operation-level parameters override inherited ones with the same name and location. */
    private List<Parameter> mergeParameters(List<Parameter> shared, List<Parameter> own) {
        Map<String, Parameter> merged = new LinkedHashMap<>();
        add(merged, shared);
        add(merged, own);
        return List.copyOf(merged.values());
    }

    private void add(Map<String, Parameter> target, List<Parameter> parameters) {
        if (parameters == null) {
            return;
        }
        for (Parameter parameter : parameters) {
            if (parameter == null || parameter.getName() == null || parameter.getIn() == null) {
                continue;
            }
            target.put(parameter.getIn() + ":" + parameter.getName(), parameter);
        }
    }
}
