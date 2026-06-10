package io.testforge.mock;

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
 * <pre>
 * forge:
 *   mock:
 *     base-url: http://wiremock.staging.example.test:8080
 *     scope-json-path: "$.metadata.test_scope"
 * </pre>
 */
@ConfigurationProperties(prefix = "forge.mock")
public record MockProperties(String baseUrl, String scopeJsonPath) {

    public MockProperties {
        if (scopeJsonPath == null || scopeJsonPath.isBlank()) {
            scopeJsonPath = "$.testScope";
        }
    }
}
