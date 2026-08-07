package io.testforge.api.discovery;

import java.util.Map;
import java.util.TreeMap;

public record ApiSchemaShape(
        String name,
        String operationKey,
        String method,
        String path,
        String direction,
        String statusCode,
        String contentType,
        Map<String, SchemaShapeEntry> fields) {

    public ApiSchemaShape {
        fields = java.util.Collections.unmodifiableMap(fields == null ? Map.of() : new TreeMap<>(fields));
    }
}
