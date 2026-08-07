package io.testforge.api.discovery;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record ApiEndpoint(
        String key,
        String method,
        String path,
        String operationId,
        List<String> tags,
        List<String> requestContentTypes,
        Map<String, List<String>> responses,
        boolean deprecated) {

    public ApiEndpoint {
        tags = List.copyOf(tags == null ? List.of() : tags);
        requestContentTypes = List.copyOf(requestContentTypes == null ? List.of() : requestContentTypes);
        responses = java.util.Collections.unmodifiableMap(responses == null ? Map.of() : new TreeMap<>(responses));
    }
}
