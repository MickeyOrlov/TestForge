package io.testforge.mock;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
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

class MockScopeSequenceTest {

    private WireMockServer server;
    private WireMock wireMock;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() {
        server = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort().bindAddress("127.0.0.1"));
        server.start();
        wireMock = WireMock.create().host("localhost").port(server.port()).build();
        // the environment's happy-path default
        server.stubFor(post(urlPathEqualTo("/payments")).atPriority(10).willReturn(okJson("{\"default\":true}")));
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private int pay(String scopeId) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/payments"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"testScope\":\"" + scopeId + "\"}")).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private MockScope scope(String id) {
        return new MockScope(wireMock, "$.testScope", id, ArtifactSink.NO_OP);
    }

    @Test
    void answersInOrderThenKeepsTheLastResponse() throws Exception {
        try (MockScope scope = scope("s-1")) {
            scope.sequence(post(urlPathEqualTo("/payments")),
                    aResponse().withStatus(503), aResponse().withStatus(502), aResponse().withStatus(201));

            assertThat(pay("s-1")).isEqualTo(503);
            assertThat(pay("s-1")).isEqualTo(502);
            assertThat(pay("s-1")).isEqualTo(201);
            assertThat(pay("s-1")).isEqualTo(201);
        }
    }

    @Test
    void parallelScopesWalkTheirOwnSequenceAndOthersGetTheDefault() throws Exception {
        try (MockScope first = scope("s-a"); MockScope second = scope("s-b")) {
            first.sequence(post(urlPathEqualTo("/payments")), aResponse().withStatus(503), aResponse().withStatus(201));
            second.sequence(post(urlPathEqualTo("/payments")), aResponse().withStatus(503), aResponse().withStatus(201));

            assertThat(pay("s-a")).isEqualTo(503);
            assertThat(pay("s-a")).isEqualTo(201);
            assertThat(pay("s-b")).as("not advanced by s-a").isEqualTo(503);
            assertThat(pay("other")).isEqualTo(200);
        }
    }

    @Test
    void closingRemovesEveryStep() throws Exception {
        try (MockScope scope = scope("s-2")) {
            assertThat(scope.sequence(post(urlPathEqualTo("/payments")),
                    aResponse().withStatus(503), aResponse().withStatus(201))).hasSize(2);
        }

        assertThat(pay("s-2")).isEqualTo(200);
        assertThat(server.getStubMappings()).hasSize(1);
    }

    @Test
    void anEmptySequenceIsRejected() {
        assertThatThrownBy(() -> scope("s-3").sequence(post(urlPathEqualTo("/payments"))))
                .hasMessageContaining("at least one response");
    }
}
