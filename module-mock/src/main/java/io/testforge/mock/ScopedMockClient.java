package io.testforge.mock;

import com.github.tomakehurst.wiremock.client.WireMock;
import io.testforge.core.context.ContextKey;
import io.testforge.core.context.ScenarioContext;
import java.util.UUID;

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

    /**
     * Where the generated scope id is published for the rest of the scenario:
     * payload builders read it to embed the id into requests so they match
     * the scoped stubs. Cleared with the scenario context (pair with
     * {@code ScenarioContextExtension}).
     */
    public static final ContextKey<String> TEST_SCOPE = ContextKey.of("TEST_SCOPE", String.class);

    private final WireMock wireMock;
    private final String scopeJsonPath;

    public ScopedMockClient(WireMock wireMock, String scopeJsonPath) {
        this.wireMock = wireMock;
        this.scopeJsonPath = scopeJsonPath;
    }

    public MockScope scope(String scopeId) {
        return new MockScope(wireMock, scopeJsonPath, scopeId);
    }

    /**
     * Opens a scope with a generated id and publishes it to
     * {@link ScenarioContext} under {@link #TEST_SCOPE}, correlating the mock
     * stubs with the scenario's outgoing payloads.
     */
    public MockScope scope() {
        String scopeId = "scope-" + UUID.randomUUID();
        ScenarioContext.put(TEST_SCOPE, scopeId);
        return scope(scopeId);
    }
}
