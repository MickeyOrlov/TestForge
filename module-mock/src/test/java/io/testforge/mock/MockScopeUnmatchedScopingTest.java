package io.testforge.mock;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.testforge.artifact.ArtifactSink;
import io.testforge.artifact.TestArtifact;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Guards the restored scope-filtered unmatched-request diagnostic (review finding B3).
 *
 * <p>The diagnostic was originally removed because {@code findAllUnmatchedRequests()} is a
 * SERVER-WIDE query on a shared WireMock, so every scope reported every other scope's
 * unmatched traffic. It is restored by filtering on the scope marker the request body
 * already carries. These tests fail if that contamination ever returns.
 */
class MockScopeUnmatchedScopingTest {

    private WireMockServer server;
    private WireMock wireMock;

    /** Captures what each scope published, without touching the filesystem. */
    private static final class RecordingSink implements ArtifactSink {
        private final Map<String, String> written = new ConcurrentHashMap<>();
        private final List<TestArtifact> registered = new CopyOnWriteArrayList<>();

        @Override
        public Path directoryFor(String source) {
            return Path.of(System.getProperty("java.io.tmpdir"), "recording-sink");
        }

        @Override
        public void register(TestArtifact artifact) {
            registered.add(artifact);
        }

        @Override
        public TestArtifact write(String source, String category, String name, String mediaType, String content) {
            written.put(name, content);
            TestArtifact a = new TestArtifact(source, category, name,
                    directoryFor(source).resolve(name), mediaType, Instant.now(), Map.of());
            registered.add(a);
            return a;
        }

        String contentFor(String name) {
            return written.get(name);
        }
    }

    @BeforeEach
    void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        wireMock = new WireMock(server.port());
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop();
        }
    }

    private void post(String body) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/no/such/endpoint"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        client.send(request, HttpResponse.BodyHandlers.discarding());
    }

    @Test
    void eachScopeReportsOnlyItsOwnUnmatchedRequests() throws Exception {
        RecordingSink sinkA = new RecordingSink();
        RecordingSink sinkB = new RecordingSink();

        try (MockScope scopeA = new MockScope(wireMock, "$.testScope", "scope-A", sinkA);
             MockScope scopeB = new MockScope(wireMock, "$.testScope", "scope-B", sinkB)) {

            scopeA.stub(WireMock.get(WireMock.urlPathEqualTo("/a")).willReturn(WireMock.ok()));
            scopeB.stub(WireMock.get(WireMock.urlPathEqualTo("/b")).willReturn(WireMock.ok()));

            // one unmatched request tagged for each scope
            post("{\"testScope\":\"scope-A\",\"marker\":\"AAA\"}");
            post("{\"testScope\":\"scope-B\",\"marker\":\"BBB\"}");
        }

        String a = sinkA.contentFor("scope-A.json");
        String b = sinkB.contentFor("scope-B.json");
        assertThat(a).isNotNull();
        assertThat(b).isNotNull();

        // Each scope sees exactly one unmatched request — its own.
        assertThat(a).contains("\"unmatchedCount\" : 1");
        assertThat(b).contains("\"unmatchedCount\" : 1");

        // And no scope leaks the other's payload marker: bodies are never stored at all.
        assertThat(a).doesNotContain("AAA").doesNotContain("BBB");
        assertThat(b).doesNotContain("AAA").doesNotContain("BBB");
    }

    @Test
    void requestWithoutScopeMarkerIsReportedInNoScope() throws Exception {
        RecordingSink sinkA = new RecordingSink();

        try (MockScope scopeA = new MockScope(wireMock, "$.testScope", "scope-A", sinkA)) {
            scopeA.stub(WireMock.get(WireMock.urlPathEqualTo("/a")).willReturn(WireMock.ok()));
            post("{\"unrelated\":true}");
            post("not json at all");
        }

        String a = sinkA.contentFor("scope-A.json");
        assertThat(a).isNotNull();
        assertThat(a)
                .as("untagged and malformed traffic must not be attributed to this scope")
                .contains("\"unmatchedCount\" : 0");
    }

    @Test
    void malformedBodyDoesNotSuppressTheWholeDiagnostic() throws Exception {
        RecordingSink sinkA = new RecordingSink();

        try (MockScope scopeA = new MockScope(wireMock, "$.testScope", "scope-A", sinkA)) {
            scopeA.stub(WireMock.get(WireMock.urlPathEqualTo("/a")).willReturn(WireMock.ok()));
            post("!!! definitely not json !!!");
            post("{\"testScope\":\"scope-A\",\"marker\":\"GOOD\"}");
        }

        String a = sinkA.contentFor("scope-A.json");
        assertThat(a).isNotNull();
        assertThat(a)
                .as("a bad body must not stop the good one being reported")
                .contains("\"unmatchedCount\" : 1");
    }
}
