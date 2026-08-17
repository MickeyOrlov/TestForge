package io.testforge.mock;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.ImmutableRequest;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.matching.RequestPattern;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class StubMismatchAnalyzerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void ranksClosestStubByDistanceInRegistrationOrder() {
        StubMapping stub0 = WireMock.get(WireMock.urlPathEqualTo("/users")).build();
        StubMapping stub1 = WireMock.post(WireMock.urlPathEqualTo("/orders"))
                .withRequestBody(WireMock.matchingJsonPath("$.scope", WireMock.equalTo("s1")))
                .build();
        StubMapping stub2 = WireMock.delete(WireMock.urlPathEqualTo("/items")).build();

        LoggedRequest req = LoggedRequest.createFrom(
                ImmutableRequest.create()
                        .withAbsoluteUrl("http://localhost:8080/orders")
                        .withMethod(RequestMethod.POST)
                        .withBody("{\"scope\":\"different\"}".getBytes(UTF_8))
                        .build()
        );

        ObjectNode result = StubMismatchAnalyzer.analyze(MAPPER, List.of(stub0, stub1, stub2), req, "$.scope");

        assertThat(result.get("closestStub").get("stubIndex").asInt()).isEqualTo(1);
    }

    @Test
    void breaksDistanceTiesByRegistrationOrder() {
        StubMapping stub0 = WireMock.post(WireMock.urlPathEqualTo("/orders")).build();
        StubMapping stub1 = WireMock.post(WireMock.urlPathEqualTo("/orders")).build();

        LoggedRequest req = LoggedRequest.createFrom(
                ImmutableRequest.create()
                        .withAbsoluteUrl("http://localhost:8080/orders")
                        .withMethod(RequestMethod.GET)
                        .build()
        );

        ObjectNode result = StubMismatchAnalyzer.analyze(MAPPER, List.of(stub0, stub1), req, "$.scope");

        assertThat(result.get("closestStub").get("stubIndex").asInt()).isEqualTo(0);
    }

    @Test
    void explainsMethodMismatch() {
        StubMapping stub = WireMock.post(WireMock.urlPathEqualTo("/orders")).build();

        LoggedRequest req = LoggedRequest.createFrom(
                ImmutableRequest.create()
                        .withAbsoluteUrl("http://localhost:8080/orders")
                        .withMethod(RequestMethod.GET)
                        .build()
        );

        ObjectNode result = StubMismatchAnalyzer.analyze(MAPPER, List.of(stub), req, "$.scope");

        ArrayNode mismatches = (ArrayNode) result.get("closestStub").get("mismatches");
        assertThat(mismatches).hasSize(1);

        JsonNode m = mismatches.get(0);
        assertThat(m.get("component").asText()).isEqualTo("method");
        assertThat(m.get("expected").asText()).isEqualTo("POST");
        assertThat(m.get("actual").asText()).isEqualTo("GET");
    }

    @Test
    void explainsUrlMismatch() {
        StubMapping stub = WireMock.get(WireMock.urlPathEqualTo("/orders")).build();

        LoggedRequest req = LoggedRequest.createFrom(
                ImmutableRequest.create()
                        .withAbsoluteUrl("http://localhost:8080/users")
                        .withMethod(RequestMethod.GET)
                        .build()
        );

        ObjectNode result = StubMismatchAnalyzer.analyze(MAPPER, List.of(stub), req, "$.scope");

        ArrayNode mismatches = (ArrayNode) result.get("closestStub").get("mismatches");
        assertThat(mismatches).hasSize(1);

        JsonNode u = mismatches.get(0);
        assertThat(u.get("component").asText()).isEqualTo("url");
        assertThat(u.get("matcher").asText()).isEqualTo("equalTo");
        assertThat(u.get("expected").asText()).isEqualTo("/orders");
        assertThat(u.get("actual").asText()).isEqualTo("/users");
    }

    @Test
    void explainsBodyMismatchWithoutValues() {
        StubMapping stub = WireMock.post(WireMock.urlPathEqualTo("/orders"))
                .withRequestBody(WireMock.matchingJsonPath("$.scope", WireMock.equalTo("s1")))
                .withRequestBody(WireMock.equalToJson("{\"foo\":\"bar\"}"))
                .build();

        LoggedRequest req = LoggedRequest.createFrom(
                ImmutableRequest.create()
                        .withAbsoluteUrl("http://localhost:8080/orders")
                        .withMethod(RequestMethod.POST)
                        .withBody("{}".getBytes(UTF_8))
                        .build()
        );

        ObjectNode result = StubMismatchAnalyzer.analyze(MAPPER, List.of(stub), req, "$.scope");

        ArrayNode mismatches = (ArrayNode) result.get("closestStub").get("mismatches");
        assertThat(mismatches).hasSize(2);

        JsonNode m0 = mismatches.get(0);
        assertThat(m0.get("component").asText()).isEqualTo("body");
        assertThat(m0.get("matcher").asText()).isEqualTo("matchesJsonPath");
        assertThat(m0.get("jsonPath").asText()).isEqualTo("$.scope");
        assertThat(m0.get("scopeMarker").asBoolean()).isTrue();
        assertThat(m0.has("expected")).isFalse();
        assertThat(m0.has("actual")).isFalse();

        JsonNode m1 = mismatches.get(1);
        assertThat(m1.get("component").asText()).isEqualTo("body");
        assertThat(m1.get("matcher").asText()).isEqualTo("equalToJson");
        assertThat(m1.has("jsonPath")).isFalse();
        assertThat(m1.get("scopeMarker").asBoolean()).isFalse();
        assertThat(m1.has("expected")).isFalse();
        assertThat(m1.has("actual")).isFalse();
    }

    @Test
    void reportsKeyNamesOnlyForHeaderQueryAndCookie() {
        StubMapping stub = WireMock.get(WireMock.urlPathEqualTo("/orders"))
                .withHeader("X-Custom-Header", WireMock.equalTo("expVal"))
                .withQueryParam("myParam", WireMock.equalTo("expParam"))
                .withCookie("myCookie", WireMock.equalTo("expCookie"))
                .build();

        LoggedRequest req = LoggedRequest.createFrom(
                ImmutableRequest.create()
                        .withAbsoluteUrl("http://localhost:8080/orders")
                        .withMethod(RequestMethod.GET)
                        .build()
        );

        ObjectNode result = StubMismatchAnalyzer.analyze(MAPPER, List.of(stub), req, "$.scope");

        ArrayNode mismatches = (ArrayNode) result.get("closestStub").get("mismatches");
        assertThat(mismatches).hasSize(3);

        JsonNode h = mismatches.get(0);
        assertThat(h.get("component").asText()).isEqualTo("header");
        assertThat(h.get("name").asText()).isEqualTo("X-Custom-Header");
        assertThat(h.has("value")).isFalse();
        assertThat(h.has("expected")).isFalse();
        assertThat(h.has("actual")).isFalse();
        assertThat(h.has("matcher")).isFalse();

        JsonNode q = mismatches.get(1);
        assertThat(q.get("component").asText()).isEqualTo("queryParam");
        assertThat(q.get("name").asText()).isEqualTo("myParam");
        assertThat(q.has("value")).isFalse();
        assertThat(q.has("expected")).isFalse();
        assertThat(q.has("actual")).isFalse();
        assertThat(q.has("matcher")).isFalse();

        JsonNode c = mismatches.get(2);
        assertThat(c.get("component").asText()).isEqualTo("cookie");
        assertThat(c.get("name").asText()).isEqualTo("myCookie");
        assertThat(c.has("value")).isFalse();
        assertThat(c.has("expected")).isFalse();
        assertThat(c.has("actual")).isFalse();
        assertThat(c.has("matcher")).isFalse();
    }

    @Test
    void reportsNoScopedStubsRegistered() {
        LoggedRequest req = LoggedRequest.createFrom(
                ImmutableRequest.create()
                        .withAbsoluteUrl("http://localhost:8080/orders")
                        .withMethod(RequestMethod.GET)
                        .build()
        );

        ObjectNode result = StubMismatchAnalyzer.analyze(MAPPER, Collections.emptyList(), req, "$.scope");

        assertThat(result.get("closestStub").isNull()).isTrue();
        assertThat(result.get("reason").asText()).isEqualTo("no scoped stubs registered");
    }

    @Test
    void neverLeaksSecretsFromStubOrRequest() {
        StubMapping stub = WireMock.post(WireMock.urlPathEqualTo("/orders"))
                .withRequestBody(WireMock.matchingJsonPath("$.user[?(@.token=='SECRET_STUB_ABC123')]"))
                .withHeader("X-Token", WireMock.equalTo("SECRET_HEADER_PATTERN"))
                .build();

        LoggedRequest req = LoggedRequest.createFrom(
                ImmutableRequest.create()
                        .withAbsoluteUrl("http://localhost:8080/orders")
                        .withMethod(RequestMethod.POST)
                        .withHeader("X-Token", "SECRET_HEADER_QQQ")
                        .withBody("{\"secret\":\"SECRET_BODY_XYZ789\"}".getBytes(UTF_8))
                        .build()
        );

        ObjectNode result = StubMismatchAnalyzer.analyze(MAPPER, List.of(stub), req, "$.scope");

        String json = result.toString();
        assertThat(json).doesNotContain("SECRET_STUB_ABC123");
        assertThat(json).doesNotContain("SECRET_BODY_XYZ789");
        assertThat(json).doesNotContain("SECRET_HEADER_QQQ");
    }

    @Test
    void publishesJsonPathOnlyForTheScopeMarker() {
        String scopePath = "$.scope";
        String nonScopePath = "$.user[?(@.token=='SECRET_STUB_ABC123')]";

        StubMapping stub = WireMock.post(WireMock.urlPathEqualTo("/orders"))
                .withRequestBody(WireMock.matchingJsonPath(scopePath, WireMock.equalTo("s1")))
                .withRequestBody(WireMock.matchingJsonPath(nonScopePath))
                .build();

        LoggedRequest req = LoggedRequest.createFrom(
                ImmutableRequest.create()
                        .withAbsoluteUrl("http://localhost:8080/orders")
                        .withMethod(RequestMethod.POST)
                        .withBody("{}".getBytes(UTF_8))
                        .build()
        );

        ObjectNode result = StubMismatchAnalyzer.analyze(MAPPER, List.of(stub), req, scopePath);

        ArrayNode mismatches = (ArrayNode) result.get("closestStub").get("mismatches");
        assertThat(mismatches).hasSize(2);

        JsonNode m0 = mismatches.get(0);
        assertThat(m0.get("component").asText()).isEqualTo("body");
        assertThat(m0.get("matcher").asText()).isEqualTo("matchesJsonPath");
        assertThat(m0.get("jsonPath").asText()).isEqualTo(scopePath);
        assertThat(m0.get("scopeMarker").asBoolean()).isTrue();

        JsonNode m1 = mismatches.get(1);
        assertThat(m1.get("component").asText()).isEqualTo("body");
        assertThat(m1.get("matcher").asText()).isEqualTo("matchesJsonPath");
        assertThat(m1.has("jsonPath")).isFalse();
        assertThat(m1.get("scopeMarker").asBoolean()).isFalse();
    }

    @Test
    void producesByteIdenticalOutputForSameInput() {
        StubMapping stub0 = WireMock.get(WireMock.urlPathEqualTo("/orders")).build();
        StubMapping stub1 = WireMock.post(WireMock.urlPathEqualTo("/orders"))
                .withRequestBody(WireMock.matchingJsonPath("$.scope", WireMock.equalTo("s1")))
                .withHeader("B-Header", WireMock.equalTo("v1"))
                .withHeader("A-Header", WireMock.equalTo("v2"))
                .build();

        LoggedRequest req = LoggedRequest.createFrom(
                ImmutableRequest.create()
                        .withAbsoluteUrl("http://localhost:8080/orders")
                        .withMethod(RequestMethod.POST)
                        .withBody("{\"scope\":\"s2\"}".getBytes(UTF_8))
                        .build()
        );

        ObjectNode result1 = StubMismatchAnalyzer.analyze(MAPPER, List.of(stub0, stub1), req, "$.scope");
        ObjectNode result2 = StubMismatchAnalyzer.analyze(MAPPER, List.of(stub0, stub1), req, "$.scope");

        assertThat(result1.toString()).isEqualTo(result2.toString());
    }

    @Test
    void degradesGracefullyWhenAMatcherThrows() {
        RequestPattern throwingReqPattern = new RequestPattern(request -> {
            throw new RuntimeException("Simulated matcher error");
        });
        StubMapping throwingStub = new StubMapping(throwingReqPattern, new ResponseDefinition());

        LoggedRequest req = LoggedRequest.createFrom(
                ImmutableRequest.create()
                        .withAbsoluteUrl("http://localhost:8080/orders")
                        .withMethod(RequestMethod.GET)
                        .build()
        );

        ObjectNode result = StubMismatchAnalyzer.analyze(MAPPER, List.of(throwingStub), req, "$.scope");

        assertThat(result).isNotNull();
        assertThat(result.has("closestStub")).isTrue();
        assertThat(result.get("closestStub").get("stubIndex").asInt()).isEqualTo(0);
    }
}
