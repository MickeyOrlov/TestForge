package io.testforge.api.explorer;

import io.swagger.v3.oas.models.OpenAPI;
import io.testforge.api.discovery.ApiSpecSource;
import io.testforge.api.discovery.OpenApiSpecParser;
import java.util.List;
import java.util.Map;

/** The demo document every test in this module works from. */
final class ExplorerFixtures {

    static final String SPEC_ID = "demo";
    static final String LOCATION = "classpath:openapi/explorer-demo.yaml";

    private ExplorerFixtures() {
    }

    static OpenAPI openApi() {
        return new OpenApiSpecParser().parse(new ApiSpecSource(SPEC_ID, LOCATION));
    }

    static List<ExplorableOperation> operations() {
        return new OperationSelector().select(SPEC_ID, openApi());
    }

    static ExplorableOperation operation(String operationId) {
        return operations().stream()
                .filter(operation -> operationId.equals(operation.operationId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No operation " + operationId));
    }

    /** A second document, so media-range cases do not change the demo's operation count. */
    static final String MEDIA_RANGE_SPEC_ID = "media-ranges";
    static final String MEDIA_RANGE_LOCATION = "classpath:openapi/explorer-media-ranges.yaml";

    static ExplorableOperation mediaRangeOperation(String operationId) {
        OpenAPI openApi = new OpenApiSpecParser()
                .parse(new ApiSpecSource(MEDIA_RANGE_SPEC_ID, MEDIA_RANGE_LOCATION));
        return new OperationSelector().select(MEDIA_RANGE_SPEC_ID, openApi).stream()
                .filter(operation -> operationId.equals(operation.operationId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No operation " + operationId));
    }

    static ApiExplorerProperties properties(Map<String, Object> overrides) {
        return new ApiExplorerProperties(
                true,
                (String) overrides.get("outputDir"),
                null,
                List.of(),
                asSet(overrides.get("methods")),
                (Boolean) overrides.get("allowUnsafeMethods"),
                asList(overrides.get("includePaths")),
                asList(overrides.get("excludePaths")),
                (Integer) overrides.get("maxOperations"),
                null,
                new ApiExplorerProperties.ParameterProperties(
                        asMap(overrides.get("parameterDefaults")), Map.of()),
                null);
    }

    @SuppressWarnings("unchecked")
    private static java.util.Set<String> asSet(Object value) {
        return (java.util.Set<String>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asList(Object value) {
        return (List<String>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> asMap(Object value) {
        return (Map<String, String>) value;
    }
}
