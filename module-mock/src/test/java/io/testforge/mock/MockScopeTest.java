package io.testforge.mock;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.testforge.artifact.ArtifactSink;
import io.testforge.artifact.TestArtifact;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockScopeTest {

    private WireMockServer wireMockServer;
    private WireMock wireMock;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
        wireMock = WireMock.create().host("localhost").port(wireMockServer.port()).build();
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void testClosePublishesMockDiagnosticsArtifactWithRecordingSink() {
        RecordingArtifactSink fakeSink = new RecordingArtifactSink();
        String scopeId = "scope-test-123";
        String secretToken = "SECRET_BEARER_987654321";

        try (MockScope scope = new MockScope(wireMock, "$.testScope", scopeId, fakeSink)) {
            scope.stub(WireMock.post(WireMock.urlPathEqualTo("/api/payments"))
                    .willReturn(WireMock.okJson("{\"secretToken\":\"" + secretToken + "\"}")));
        }

        List<TestArtifact> artifacts = fakeSink.registered();
        assertEquals(1, artifacts.size(), "Should publish exactly one diagnostic artifact on scope close");

        TestArtifact artifact = artifacts.get(0);
        assertEquals("module-mock", artifact.source());
        assertEquals("mock-diagnostics", artifact.category());
        assertTrue(artifact.name().contains(scopeId), "Artifact name should contain scopeId");
        assertEquals("application/json", artifact.mediaType());

        String content = fakeSink.contentFor(artifact.name());
        assertNotNull(content, "Written JSON content should be recorded");
        assertTrue(content.contains(scopeId), "Diagnostic should contain scopeId");
        assertTrue(content.contains("/api/payments"), "Diagnostic should contain URL path");
        assertTrue(content.contains("POST"), "Diagnostic should contain HTTP method");
        assertTrue(content.contains("200"), "Diagnostic should contain status code");

        assertFalse(content.contains(secretToken),
                "Diagnostic artifact MUST NOT contain raw response body/headers secret strings");
    }

    @Test
    void testNoOpSinkPublishesNothingAndDoesNotThrow() {
        String scopeId = "scope-noop-456";

        MockScope scope = new MockScope(wireMock, "$.testScope", scopeId, ArtifactSink.NO_OP);
        scope.stub(WireMock.get(WireMock.urlPathEqualTo("/api/status")).willReturn(WireMock.ok()));
        scope.close();

        // WireMock should have no stubs left
        assertEquals(0, wireMockServer.getStubMappings().size(), "Stubs should be removed on close");
    }

    @Test
    void testBestEffortSwallowsExceptionsFromSink() {
        ArtifactSink throwingSink = new ArtifactSink() {
            @Override
            public Path directoryFor(String source) {
                return Path.of("/non-existent/dir");
            }

            @Override
            public void register(TestArtifact artifact) {
                throw new RuntimeException("Simulated registry failure");
            }

            @Override
            public TestArtifact write(String source, String category, String name, String mediaType, String content) {
                throw new RuntimeException("Simulated write failure");
            }
        };

        MockScope scope = new MockScope(wireMock, "$.testScope", "scope-throw-789", throwingSink);
        scope.stub(WireMock.get(WireMock.urlPathEqualTo("/api/test")).willReturn(WireMock.ok()));

        // Closing should swallow the exception and still clean up stubs
        scope.close();
        assertEquals(0, wireMockServer.getStubMappings().size(), "Stubs should be removed even if sink fails");
    }

    @Test
    void testUnmatchedRequestsIncludedWithoutRawPayloads() throws IOException, InterruptedException {
        RecordingArtifactSink fakeSink = new RecordingArtifactSink();
        String scopeId = "scope-unmatched-111";
        String rawSecretPayload = "SUPER_SECRET_UNMATCHED_INPUT";

        try (MockScope scope = new MockScope(wireMock, "$.testScope", scopeId, fakeSink)) {
            scope.stub(WireMock.get(WireMock.urlPathEqualTo("/api/dummy")).willReturn(WireMock.ok()));

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(wireMockServer.baseUrl() + "/unmatched/endpoint"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"secret\":\"" + rawSecretPayload + "\"}"))
                    .build();
            client.send(request, HttpResponse.BodyHandlers.discarding());
        }

        List<TestArtifact> artifacts = fakeSink.registered();
        assertEquals(1, artifacts.size());

        String content = fakeSink.contentFor(artifacts.get(0).name());
        System.out.println("DEBUG UNMATCHED CONTENT: " + content);
        assertNotNull(content);
        assertTrue(content.contains("/unmatched/endpoint"), "Diagnostic should contain unmatched request URL path");
        assertFalse(content.contains(rawSecretPayload), "Diagnostic MUST NOT contain unmatched request raw body payload");
    }

    private static class RecordingArtifactSink implements ArtifactSink {
        private final List<TestArtifact> registered = new ArrayList<>();
        private final Map<String, String> writtenContents = new ConcurrentHashMap<>();

        @Override
        public Path directoryFor(String source) {
            return Path.of(System.getProperty("java.io.tmpdir"), "testforge-test-artifacts", source);
        }

        @Override
        public void register(TestArtifact artifact) {
            registered.add(artifact);
        }

        @Override
        public TestArtifact write(String source, String category, String name, String mediaType, String content) {
            Path file = directoryFor(source).resolve(name);
            TestArtifact artifact = new TestArtifact(source, category, name, file, mediaType, Instant.now(), Map.of());
            writtenContents.put(name, content);
            registered.add(artifact);
            return artifact;
        }

        public List<TestArtifact> registered() {
            return registered;
        }

        public String contentFor(String name) {
            return writtenContents.get(name);
        }
    }
}
