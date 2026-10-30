package io.testforge.mock;

import java.util.stream.Stream;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for a shared WireMock instance.
 *
 * <p>{@code scope-json-path} is the JSON path inside request bodies that
 * carries the scenario identifier (for example, an explicit test scope,
 * correlation id, or request id that the system under test echoes into
 * downstream calls). This is the main adaptation point per project: find the
 * field that uniquely ties a backend request to one test scenario and point
 * this path at it.
 *
 * <p>Requests without a body (GET, DELETE) cannot carry a body marker. Set
 * exactly one of {@code scope-header}, {@code scope-query-param} or
 * {@code scope-cookie} instead; it replaces the JSON path as the scope marker.
 *
 * <pre>
 * forge:
 *   mock:
 *     base-url: http://wiremock.staging.example.test:8080
 *     scope-json-path: "$.metadata.test_scope"
 *     # or: scope-header: X-Test-Scope
 * </pre>
 */
@ConfigurationProperties(prefix = "forge.mock")
public record MockProperties(
        String baseUrl,
        String scopeJsonPath,
        String scopeHeader,
        String scopeQueryParam,
        String scopeCookie) {

    public MockProperties {
        if (scopeJsonPath == null || scopeJsonPath.isBlank()) {
            scopeJsonPath = "$.testScope";
        }
        long alternatives = Stream.of(scopeHeader, scopeQueryParam, scopeCookie)
                .filter(value -> value != null && !value.isBlank())
                .count();
        if (alternatives > 1) {
            throw new IllegalArgumentException(
                    "Set at most one of forge.mock.scope-header, scope-query-param, scope-cookie: "
                            + "a scope is matched in exactly one place");
        }
    }

    /** The configured marker: header, query parameter or cookie when set, otherwise the body JSON path. */
    public ScopeMarker scopeMarker() {
        if (scopeHeader != null && !scopeHeader.isBlank()) {
            return ScopeMarker.header(scopeHeader);
        }
        if (scopeQueryParam != null && !scopeQueryParam.isBlank()) {
            return ScopeMarker.queryParam(scopeQueryParam);
        }
        if (scopeCookie != null && !scopeCookie.isBlank()) {
            return ScopeMarker.cookie(scopeCookie);
        }
        return ScopeMarker.jsonPath(scopeJsonPath);
    }
}
