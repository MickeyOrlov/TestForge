package io.testforge.mock;

import com.github.tomakehurst.wiremock.client.WireMock;
import io.testforge.artifact.ArtifactSink;
import io.testforge.core.context.ContextKey;
import io.testforge.core.context.ScenarioContext;
import io.testforge.core.context.ScenarioKeys;
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
     * the scoped stubs, and {@code module-http} injects it automatically.
     * Cleared with the scenario context (pair with
     * {@code ScenarioContextExtension}).
     */
    public static final ContextKey<String> TEST_SCOPE = ScenarioKeys.TEST_SCOPE;

    private final WireMock wireMock;
    private final String scopeJsonPath;
    private final ArtifactSink artifactSink;

    public ScopedMockClient(WireMock wireMock, String scopeJsonPath) {
        this(wireMock, scopeJsonPath, ArtifactSink.NO_OP);
    }

    public ScopedMockClient(WireMock wireMock, String scopeJsonPath, ArtifactSink artifactSink) {
        this.wireMock = wireMock;
        this.scopeJsonPath = scopeJsonPath;
        this.artifactSink = artifactSink != null ? artifactSink : ArtifactSink.NO_OP;
    }

    public MockScope scope(String scopeId) {
        return new MockScope(wireMock, scopeJsonPath, scopeId, artifactSink);
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
