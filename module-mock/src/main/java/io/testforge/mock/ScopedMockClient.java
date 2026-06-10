package io.testforge.mock;

import com.github.tomakehurst.wiremock.client.WireMock;

/**
 * Entry point for scenario-scoped stubbing on a shared WireMock server.
 *
 * <pre>{@code
 * try (MockScope scope = scopedMockClient.scope(scopeId)) {
 *     scope.stub(post(urlPathEqualTo("/downstream/status"))
 *             .willReturn(okJson("{\"result\":\"scenario-specific\"}")));
 *     // ... run the scenario ...
 * } // stubs are removed here
 * }</pre>
 */
public class ScopedMockClient {

    private final WireMock wireMock;
    private final String scopeJsonPath;

    public ScopedMockClient(WireMock wireMock, String scopeJsonPath) {
        this.wireMock = wireMock;
        this.scopeJsonPath = scopeJsonPath;
    }

    public MockScope scope(String scopeId) {
        return new MockScope(wireMock, scopeJsonPath, scopeId);
    }
}
