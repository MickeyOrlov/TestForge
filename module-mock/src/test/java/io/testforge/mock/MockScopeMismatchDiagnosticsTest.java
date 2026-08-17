package io.testforge.mock;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

class MockScopeMismatchDiagnosticsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WireMockServer server;
    private WireMock wireMock;

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

    private void post(String path, String body) throws Exception {
        post(path, body, Map.of());
    }

    private void post(String path, String body, Map<String, String> headers) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + path))
                .header("Content-Type", "application/json");
        headers.forEach(builder::header);
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
        client.send(request, HttpResponse.BodyHandlers.discarding());
    }

    @Test
    void unmatchedRequestGainsClosestStubExplanation() throws Exception {
        RecordingSink sink = new RecordingSink();
        String scopeId = "scope-1";
        try (MockScope scope = new MockScope(wireMock, "$.testScope", scopeId, sink)) {
            scope.stub(WireMock.post(WireMock.urlPathEqualTo("/orders")).willReturn(WireMock.ok()));
            post("/no/such/endpoint", "{\"testScope\":\"" + scopeId + "\"}");
        }

        String json = sink.contentFor(scopeId + ".json");
        assertThat(json).isNotNull();

        JsonNode root = MAPPER.readTree(json);
        JsonNode unmatched = root.path("unmatchedRequests").get(0);
        assertThat(unmatched).isNotNull();
        JsonNode closestStub = unmatched.path("closestStub");
        assertThat(closestStub.isObject()).isTrue();
        assertThat(closestStub.has("stubIndex")).isTrue();
        assertThat(closestStub.has("distance")).isTrue();
    }

    @Test
    void explainsWhyTheClosestStubDidNotMatch() throws Exception {
        RecordingSink sink = new RecordingSink();
        String scopeId = "scope-2";
        try (MockScope scope = new MockScope(wireMock, "$.testScope", scopeId, sink)) {
            scope.stub(WireMock.post(WireMock.urlPathEqualTo("/orders")).willReturn(WireMock.ok()));
            post("/no/such/endpoint", "{\"testScope\":\"" + scopeId + "\"}");
        }

        String json = sink.contentFor(scopeId + ".json");
        assertThat(json).isNotNull();

        JsonNode root = MAPPER.readTree(json);
        JsonNode unmatched = root.path("unmatchedRequests").get(0);
        JsonNode closestStub = unmatched.path("closestStub");
        JsonNode mismatches = closestStub.path("mismatches");

        assertThat(mismatches.isArray()).isTrue();
        assertThat(mismatches.size()).isGreaterThan(0);

        boolean hasUrlComponent = false;
        for (JsonNode m : mismatches) {
            if ("url".equals(m.path("component").asText())) {
                hasUrlComponent = true;
                break;
            }
        }
        assertThat(hasUrlComponent).isTrue();
    }

    @Test
    void reportsNoScopedStubsRegisteredWhenScopeRegisteredNone() throws Exception {
        RecordingSink sink = new RecordingSink();
        String scopeId = "scope-3";
        try (MockScope scope = new MockScope(wireMock, "$.testScope", scopeId, sink)) {
            post("/no/such/endpoint", "{\"testScope\":\"" + scopeId + "\"}");
        }

        String json = sink.contentFor(scopeId + ".json");
        assertThat(json).isNotNull();

        JsonNode root = MAPPER.readTree(json);
        JsonNode unmatched = root.path("unmatchedRequests").get(0);
        assertThat(unmatched).isNotNull();
        JsonNode closestStub = unmatched.get("closestStub");
        assertThat(closestStub.isNull()).isTrue();
        assertThat(unmatched.path("reason").asText()).isEqualTo("no scoped stubs registered");
    }

    @Test
    void doesNotNameAnotherScopesStub() throws Exception {
        RecordingSink sinkA = new RecordingSink();
        RecordingSink sinkB = new RecordingSink();

        try (MockScope scopeA = new MockScope(wireMock, "$.testScope", "scope-A", sinkA);
             MockScope scopeB = new MockScope(wireMock, "$.testScope", "scope-B", sinkB)) {

            scopeA.stub(WireMock.post(WireMock.urlPathEqualTo("/orders"))
                    .withName("Stub-Scope-A-Unique-Name")
                    .willReturn(WireMock.ok()));
            scopeB.stub(WireMock.post(WireMock.urlPathEqualTo("/payments"))
                    .withName("Stub-Scope-B-Unique-Name")
                    .willReturn(WireMock.ok()));

            post("/no/such/endpoint", "{\"testScope\":\"scope-A\"}");
            post("/no/such/endpoint", "{\"testScope\":\"scope-B\"}");
        }

        String jsonA = sinkA.contentFor("scope-A.json");
        String jsonB = sinkB.contentFor("scope-B.json");

        assertThat(jsonA).isNotNull();
        assertThat(jsonB).isNotNull();

        assertThat(jsonA).doesNotContain("Stub-Scope-B-Unique-Name");
        assertThat(jsonB).doesNotContain("Stub-Scope-A-Unique-Name");
    }

    @Test
    void neverPublishesRequestBodyOrHeaderValues() throws Exception {
        RecordingSink sink = new RecordingSink();
        String scopeId = "scope-5";
        try (MockScope scope = new MockScope(wireMock, "$.testScope", scopeId, sink)) {
            scope.stub(WireMock.post(WireMock.urlPathEqualTo("/secret"))
                    .withRequestBody(WireMock.matchingJsonPath("$.secret", WireMock.equalTo("SECRET_STUB_ABC123")))
                    .withHeader("X-Secret", WireMock.equalTo("EXPECTED_VAL"))
                    .willReturn(WireMock.ok()));

            post("/secret",
                    "{\"testScope\":\"" + scopeId + "\",\"secret\":\"SECRET_BODY_XYZ789\"}",
                    Map.of("X-Secret", "SECRET_HEADER_QQQ"));
        }

        String json = sink.contentFor(scopeId + ".json");
        assertThat(json).isNotNull();

        assertThat(json)
                .doesNotContain("SECRET_STUB_ABC123")
                .doesNotContain("SECRET_BODY_XYZ789")
                .doesNotContain("SECRET_HEADER_QQQ");
    }
}
