package io.testforge.mock;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.VerificationException;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import io.testforge.artifact.ArtifactSink;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** GET requests have no body: these markers are what makes them scopeable at all. */
class ScopeMarkerTest {

    private WireMockServer server;
    private WireMock wireMock;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() {
        server = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort().bindAddress("127.0.0.1"));
        server.start();
        wireMock = WireMock.create().host("localhost").port(server.port()).build();
        server.stubFor(get(urlPathEqualTo("/status")).atPriority(10).willReturn(okJson("{\"from\":\"default\"}")));
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    /** How a request carries a scope id for each marker kind. */
    static Stream<Arguments> markers() {
        return Stream.of(
                Arguments.of(ScopeMarker.header("X-Test-Scope"),
                        (BiFunction<String, String, HttpRequest.Builder>) (base, id) ->
                                HttpRequest.newBuilder(URI.create(base + "/status")).header("X-Test-Scope", id)),
                Arguments.of(ScopeMarker.queryParam("scope"),
                        (BiFunction<String, String, HttpRequest.Builder>) (base, id) ->
                                HttpRequest.newBuilder(URI.create(base + "/status?scope=" + id))),
                Arguments.of(ScopeMarker.cookie("test_scope"),
                        (BiFunction<String, String, HttpRequest.Builder>) (base, id) ->
                                HttpRequest.newBuilder(URI.create(base + "/status")).header("Cookie", "test_scope=" + id)));
    }

    private String call(BiFunction<String, String, HttpRequest.Builder> request, String scopeId) throws Exception {
        String base = "http://localhost:" + server.port();
        HttpRequest.Builder builder = scopeId == null
                ? HttpRequest.newBuilder(URI.create(base + "/status"))
                : request.apply(base, scopeId);
        return http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    @ParameterizedTest
    @MethodSource("markers")
    void twoScenariosOnOneGetPathNeverSeeEachOthersStubs(ScopeMarker marker,
            BiFunction<String, String, HttpRequest.Builder> request) throws Exception {
        try (MockScope a = new MockScope(wireMock, marker, "scope-A", ArtifactSink.NO_OP);
             MockScope b = new MockScope(wireMock, marker, "scope-B", ArtifactSink.NO_OP)) {
            a.stub(get(urlPathEqualTo("/status")).willReturn(okJson("{\"from\":\"A\"}")));
            b.stub(get(urlPathEqualTo("/status")).willReturn(okJson("{\"from\":\"B\"}")));

            assertThat(call(request, "scope-A")).contains("\"A\"");
            assertThat(call(request, "scope-B")).contains("\"B\"");
            assertThat(call(request, null)).as("unscoped traffic hits the default").contains("default");
        }
    }

    @ParameterizedTest
    @MethodSource("markers")
    void theJournalAndVerifyCountOnlyThisScenariosTraffic(ScopeMarker marker,
            BiFunction<String, String, HttpRequest.Builder> request) throws Exception {
        try (MockScope a = new MockScope(wireMock, marker, "scope-A", ArtifactSink.NO_OP)) {
            call(request, "scope-A");
            call(request, "scope-A");
            call(request, "scope-B");
            call(request, null);

            assertThat(a.requests()).hasSize(2);
            assertThat(a.requests(getRequestedFor(urlPathEqualTo("/status")))).hasSize(2);
            assertThat(a.requests(getRequestedFor(urlPathEqualTo("/other")))).isEmpty();
            a.verify(2, getRequestedFor(urlPathEqualTo("/status")));
            assertThatThrownBy(() -> a.verify(3, getRequestedFor(urlPathEqualTo("/status"))))
                    .isInstanceOf(VerificationException.class);
        }
    }

    @Test
    void verifyNeverCountsAnotherScenariosRequests() throws Exception {
        ScopeMarker marker = ScopeMarker.header("X-Test-Scope");
        try (MockScope a = new MockScope(wireMock, marker, "scope-A", ArtifactSink.NO_OP)) {
            http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/status"))
                    .header("X-Test-Scope", "scope-B").GET().build(), HttpResponse.BodyHandlers.discarding());

            a.verify(0, RequestPatternBuilder.allRequests());
        }
    }

    @Test
    void unmatchedDiagnosticsFollowAHeaderMarker() throws Exception {
        java.util.List<String> written = new java.util.ArrayList<>();
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
                written.add(content);
                return ArtifactSink.NO_OP.write(source, category, name, mediaType, content);
            }
        };
        ScopeMarker marker = ScopeMarker.header("X-Test-Scope");
        String base = "http://localhost:" + server.port();
        server.resetMappings();   // no default stub: every request below is unmatched

        try (MockScope a = new MockScope(wireMock, marker, "scope-A", sink)) {
            a.stub(get(urlPathEqualTo("/status")).willReturn(okJson("{}")));
            http.send(HttpRequest.newBuilder(URI.create(base + "/missing")).header("X-Test-Scope", "scope-A")
                    .GET().build(), HttpResponse.BodyHandlers.discarding());
            http.send(HttpRequest.newBuilder(URI.create(base + "/missing")).header("X-Test-Scope", "scope-B")
                    .GET().build(), HttpResponse.BodyHandlers.discarding());
        }

        JsonNode diagnostic = new ObjectMapper().readTree(written.getFirst());
        assertThat(diagnostic.get("scopeMarker").get("kind").asText()).isEqualTo("HEADER");
        assertThat(diagnostic.get("scopeMarker").get("name").asText()).isEqualTo("X-Test-Scope");
        assertThat(diagnostic.has("scopeJsonPath")).isFalse();
        assertThat(diagnostic.get("unmatchedRequests")).hasSize(1);
        assertThat(diagnostic.toString()).doesNotContain("scope-B");
    }

    @Test
    void markersRejectBlankNames() {
        assertThatThrownBy(() -> ScopeMarker.header(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void propertiesPickTheConfiguredMarker() {
        assertThat(new MockProperties("u", null, null, null, null, null).scopeMarker())
                .isEqualTo(ScopeMarker.jsonPath("$.testScope"));
        assertThat(new MockProperties("u", "$.x", "X-Scope", null, null, null).scopeMarker())
                .as("an explicit header replaces the body path")
                .isEqualTo(ScopeMarker.header("X-Scope"));
        assertThat(new MockProperties("u", null, null, "scope", " ", null).scopeMarker())
                .isEqualTo(ScopeMarker.queryParam("scope"));
        assertThat(new MockProperties("u", null, null, null, "sc", null).scopeMarker()).isEqualTo(ScopeMarker.cookie("sc"));
    }

    @Test
    void configuringTwoMarkersIsRejected() {
        assertThatThrownBy(() -> new MockProperties("u", null, "X-Scope", "scope", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most one");
    }
}
