package io.testforge.api.fuzz;

import io.testforge.api.discovery.ApiSpecSource;
import io.testforge.api.discovery.OpenApiSpecParser;
import io.testforge.api.explorer.ExplorableOperation;
import io.testforge.api.explorer.OperationSelector;
import java.util.List;

/** The demo document every test in this module works from. */
final class FuzzFixtures {

    static final String SPEC_ID = "demo";
    static final String LOCATION = "classpath:openapi/fuzz-demo.yaml";

    private FuzzFixtures() {
    }

    static List<ExplorableOperation> operations() {
        return new OperationSelector().select(SPEC_ID,
                new OpenApiSpecParser().parse(new ApiSpecSource(SPEC_ID, LOCATION)));
    }

    static ExplorableOperation operation(String operationId) {
        return operations().stream()
                .filter(operation -> operationId.equals(operation.operationId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No operation " + operationId));
    }
}
