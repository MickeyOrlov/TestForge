package io.testforge.api.explorer;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * A request the explorer decided it can build, before it is sent.
 *
 * <p>It keeps the templated path next to the resolved one on purpose. The
 * template is the operation's identity across runs and environments — reports
 * and artifact names key off it — while the resolved path is what actually
 * went out and what a later replay would need.
 */
public record PreparedRequest(
        String method,
        String pathTemplate,
        Map<String, String> pathParameters,
        Map<String, String> queryParameters,
        String body,
        String contentType) {

    /**
     * A request with no body — what exploration always builds, since it never
     * synthesizes one. The body fields exist for {@code module-api-fuzz}, which
     * does derive bodies from the schema; putting them here rather than in a
     * parallel type keeps a single request shape and a single executor.
     */
    public PreparedRequest(String method, String pathTemplate,
                           Map<String, String> pathParameters, Map<String, String> queryParameters) {
        this(method, pathTemplate, pathParameters, queryParameters, null, null);
    }

    public PreparedRequest {
        // sorted rather than Map.copyOf: an immutable map has no defined
        // iteration order, and the query string built from it has to be
        // byte-identical between runs
        pathParameters = sorted(pathParameters);
        queryParameters = sorted(queryParameters);
    }

    private static Map<String, String> sorted(Map<String, String> values) {
        return Collections.unmodifiableMap(new TreeMap<>(values == null ? Map.of() : values));
    }

    /** The path with {@code {placeholders}} substituted. */
    public String resolvedPath() {
        String resolved = pathTemplate;
        for (Map.Entry<String, String> parameter : pathParameters.entrySet()) {
            resolved = resolved.replace("{" + parameter.getKey() + "}", parameter.getValue());
        }
        return resolved;
    }

    /** Resolved path plus a deterministically ordered query string. */
    public String resolvedTarget() {
        String path = resolvedPath();
        if (queryParameters.isEmpty()) {
            return path;
        }

        StringBuilder query = new StringBuilder();
        queryParameters.forEach((name, value) ->
                query.append(query.isEmpty() ? '?' : '&').append(name).append('=').append(value));
        return path + query;
    }
}
