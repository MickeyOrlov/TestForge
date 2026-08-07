package io.testforge.api.explorer;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.util.List;

/**
 * One operation of one document, with its parameters already merged from the
 * path item and the operation itself.
 *
 * <p>The swagger model rides along rather than being copied into a private
 * shape: {@code module-api-discovery} already resolved the document fully, so
 * schemas here are concrete and a second model would only be a chance to drift
 * from it.
 */
public record ExplorableOperation(
        String specId,
        String operationId,
        String method,
        String pathTemplate,
        boolean deprecated,
        List<Parameter> parameters,
        Operation operation) {

    public ExplorableOperation {
        parameters = List.copyOf(parameters == null ? List.of() : parameters);
    }

    /** {@code GET /api/v1/tasks} — how the operation appears in reports and logs. */
    public String key() {
        return method + " " + pathTemplate;
    }
}
