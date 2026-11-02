package io.testforge.example.apiload;

import io.swagger.v3.oas.models.OpenAPI;
import io.testforge.api.explorer.ExplorableOperation;
import io.testforge.api.explorer.OperationSelector;
import java.util.List;
import java.util.Set;

/** Project assembly narrows the existing selector by exact operation keys, never method/path cross-products. */
final class SelectedApiOperations extends OperationSelector {
    private final Set<String> keys;

    SelectedApiOperations(List<String> keys) {
        this.keys = Set.copyOf(keys);
    }

    @Override
    public List<ExplorableOperation> select(String specId, OpenAPI openApi) {
        return super.select(specId, openApi).stream().filter(operation -> keys.contains(operation.key())).toList();
    }
}
