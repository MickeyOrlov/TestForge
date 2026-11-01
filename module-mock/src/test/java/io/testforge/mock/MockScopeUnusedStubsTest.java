package io.testforge.mock;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.testforge.artifact.ArtifactSink;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MockScopeUnusedStubsTest {

    private WireMockServer server;
    private WireMock wireMock;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() {
        server = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort().bindAddress("127.0.0.1"));
        server.start();
        wireMock = WireMock.create().host("localhost").port(server.port()).build();
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

    private MockScope scope(String id, boolean strict) {
        return new MockScope(wireMock, ScopeMarker.jsonPath("$.testScope"), id, ArtifactSink.NO_OP, strict);
    }

    @Test
    void aStubNoRequestHitIsNamed() throws Exception {
        try (MockScope scope = scope("s-1", false)) {
            scope.stub(post(urlPathEqualTo("/payments")).willReturn(okJson("{}")));
            scope.stub(post(urlPathEqualTo("/refunds")).willReturn(aResponse().withStatus(409)));

            call("/payments", "s-1");
            call("/refunds", "someone-else");

            assertThat(scope.unusedStubs()).containsExactly("POST /refunds → 409");
        }
    }

    @Test
    void aSequenceStepNotReachedSaysTheSystemStoppedEarly() throws Exception {
        try (MockScope scope = scope("s-2", false)) {
            scope.sequence(post(urlPathEqualTo("/payments")),
                    aResponse().withStatus(503), aResponse().withStatus(503), aResponse().withStatus(201));

            call("/payments", "s-2");
            call("/payments", "s-2");

            assertThat(scope.unusedStubs()).containsExactly("POST /payments, sequence step-3 → 201");
        }
    }

    @Test
    void strictScopesFailOnCloseAfterRemovingTheirStubs() {
        MockScope scope = scope("s-3", true);
        scope.stub(get(urlPathEqualTo("/limits")).willReturn(okJson("{}")));

        assertThatThrownBy(scope::close)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Scope s-3: stubs the scenario never called")
                .hasMessageContaining("GET /limits → 200");
        assertThat(server.getStubMappings()).isEmpty();
    }

    @Test
    void aStrictScopeWhoseStubsWereAllUsedClosesQuietly() throws Exception {
        try (MockScope scope = scope("s-4", true)) {
            scope.stub(post(urlPathEqualTo("/payments")).willReturn(okJson("{}")));
            call("/payments", "s-4");
        }
    }

    @Test
    void whenTheTestBodyFailedTheUnusedStubIsASuppressedNote() {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> {
            try (MockScope scope = scope("s-5", true)) {
                scope.stub(post(urlPathEqualTo("/payments")).willReturn(okJson("{}")));
                throw new IllegalStateException("the test's own failure");
            }
        });

        assertThat(thrown).hasMessage("the test's own failure");
        assertThat(thrown.getSuppressed()).singleElement()
                .satisfies(note -> assertThat(note).hasMessageContaining("never called"));
    }

    @Test
    void aBlankScopeIdIsRejectedBecauseItWouldMatchUnscopedTraffic() {
        assertThatThrownBy(() -> scope(" ", false)).hasMessageContaining("non-blank id");
        assertThatThrownBy(() -> scope(null, false)).hasMessageContaining("non-blank id");
    }

    @Test
    void aSecondCloseDoesNothing() {
        java.util.List<String> written = new java.util.concurrent.CopyOnWriteArrayList<>();
        ArtifactSink sink = new ArtifactSink() {
            @Override
            public java.nio.file.Path directoryFor(String source) {
                return java.nio.file.Path.of("unused");
            }

            @Override
            public void register(io.testforge.artifact.TestArtifact artifact) {
            }

            @Override
            public io.testforge.artifact.TestArtifact write(String source, String category, String name,
                                                            String mediaType, String content) {
                written.add(name + "\n" + content);
                return ArtifactSink.NO_OP.write(source, category, name, mediaType, content);
            }
        };
        MockScope scope = new MockScope(wireMock, ScopeMarker.jsonPath("$.testScope"), "s-6", sink, true);
        scope.stub(post(urlPathEqualTo("/payments")).willReturn(okJson("{}")));

        assertThatThrownBy(scope::close).isInstanceOf(AssertionError.class);
        scope.close();

        assertThat(written).singleElement().satisfies(content -> assertThat(content).contains("/payments"));
    }
}
