package io.testforge.mock;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MockSummaryTest {

    @TempDir
    Path runDir;

    private WireMockServer server;
    private WireMock wireMock;
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<TestArtifact> registered = new CopyOnWriteArrayList<>();
    private ArtifactSink sink;

    @BeforeEach
    void start() {
        server = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort().bindAddress("127.0.0.1"));
        server.start();
        wireMock = WireMock.create().host("localhost").port(server.port()).build();
        sink = new ArtifactSink() {
            @Override
            public Path directoryFor(String source) {
                return runDir.resolve(source);
            }

            @Override
            public void register(TestArtifact artifact) {
                registered.add(artifact);
            }

            @Override
            public TestArtifact write(String source, String category, String name, String mediaType, String content) {
                return ArtifactSink.NO_OP.write(source, category, name, mediaType, content);
            }
        };
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private void call(String path, String scopeId) throws Exception {
        http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"testScope\":\"" + scopeId + "\"}")).build(),
                HttpResponse.BodyHandlers.discarding());
    }

    @Test
    void leftoversOfEveryClosedScopeAreCountedForTheRun() throws Exception {
        MockSummary summary = new MockSummary(sink);
        ScopedMockClient client = new ScopedMockClient(wireMock, ScopeMarker.jsonPath("$.testScope"), sink, false,
                summary);
        for (String id : List.of("s-1", "s-2")) {
            try (MockScope scope = client.scope(id)) {
                scope.stub(post(urlPathEqualTo("/payments")).willReturn(okJson("{}")));
                scope.stub(post(urlPathEqualTo("/refunds")).willReturn(aResponse().withStatus(409)));
                call("/payments", id);
                call("/ledger?account=secret", id);
            }
        }
        try (MockScope scope = client.scope("s-3")) {
            scope.stub(post(urlPathEqualTo("/payments")).willReturn(okJson("{}")));
            call("/payments", "s-3");
        }

        summary.stop();

        assertThat(registered).singleElement().satisfies(artifact -> {
            assertThat(artifact.name()).isEqualTo("mock-summary.json");
            assertThat(artifact.category()).isEqualTo("mock-summary");
        });
        JsonNode json = new ObjectMapper().readTree(runDir.resolve("module-mock/mock-summary.json").toFile());
        assertThat(json.path("formatVersion").asInt()).isEqualTo(1);
        assertThat(json.path("scopes").asLong()).isEqualTo(3);
        assertThat(json.path("stubs").asLong()).isEqualTo(5);
        assertThat(json.path("scopesWithUnusedStubs").asLong()).isEqualTo(2);
        assertThat(json.path("unusedStubs").get(0).path("stub").asText()).isEqualTo("POST /refunds → 409");
        assertThat(json.path("unusedStubs").get(0).path("scopes").asLong()).isEqualTo(2);
        assertThat(json.path("unmatchedRequests")).singleElement().satisfies(request -> {
            assertThat(request.path("request").asText()).as("no query value is kept").isEqualTo("POST /ledger");
            assertThat(request.path("count").asLong()).isEqualTo(2);
        });
    }

    @Test
    void nothingIsWrittenWithoutAClosedScope() {
        new MockSummary(sink).stop();

        assertThat(registered).isEmpty();
    }

    @Test
    void distinctEntriesAreBounded() {
        MockSummary summary = new MockSummary(sink);
        for (int i = 0; i < MockSummary.MAX_ENTRIES + 3; i++) {
            summary.record(1, List.of("GET /items/" + i + " → 200"), List.of());
        }

        assertThat((List<?>) summary.summary().get("unusedStubs")).hasSize(MockSummary.MAX_ENTRIES);
        assertThat(summary.summary().get("droppedEntries")).isEqualTo(3L);
    }
}
