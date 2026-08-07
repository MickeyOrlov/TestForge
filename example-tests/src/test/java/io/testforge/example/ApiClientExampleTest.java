package io.testforge.example;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.restassured.http.ContentType;
import io.testforge.core.context.ScenarioContext;
import io.testforge.core.context.ScenarioContextExtension;
import io.testforge.core.context.ScenarioKeys;
import io.testforge.http.ApiClient;
import io.testforge.mock.MockScope;
import io.testforge.mock.ScopedMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The correlation loop with no bookkeeping in the test body: opening a mock
 * scope is enough for the outgoing request to carry the scope id and land on
 * that scenario's stub.
 *
 * <p>Compare with {@code ScopedRequestTemplateTest}, which wires the same loop
 * by hand — that stays the pattern for payloads built outside the API client.
 *
 * <p>Note that {@code forge.http.scope.json-path} is never set here: the HTTP
 * module reads {@code forge.mock.scope-json-path}, so the field can only be
 * configured in one place.
 */
@SpringBootTest
@ExtendWith(ScenarioContextExtension.class)
class ApiClientExampleTest {

    static WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        server.start();
    }

    @DynamicPropertySource
    static void forgeProperties(DynamicPropertyRegistry registry) {
        registry.add("forge.mock.base-url", () -> "http://localhost:" + server.port());
        registry.add("forge.mock.scope-json-path", () -> "$.metadata.test_scope");
        registry.add("forge.http.base-url", () -> "http://localhost:" + server.port());
    }

    @Autowired
    ApiClient api;

    @Autowired
    ScopedMockClient mocks;

    @BeforeEach
    void defaultStubs() {
        server.resetAll();
        server.stubFor(post(urlPathEqualTo("/payments"))
                .atPriority(10)
                .willReturn(okJson("{\"result\":\"default\"}")));
        server.stubFor(get(urlPathEqualTo("/health"))
                .willReturn(okJson("{\"status\":\"UP\"}")));
    }

    @Test
    void requestsCarryTheScopeOfTheOpenScenario() {
        try (MockScope scope = mocks.scope()) {
            scope.stub(post(urlPathEqualTo("/payments"))
                    .willReturn(okJson("{\"result\":\"scoped\"}")));

            // the payload knows nothing about the scope — the filter embeds it
            String response = api.request()
                    .contentType(ContentType.JSON)
                    .body("{\"amount\":100}")
                    .post("/payments")
                    .then()
                    .statusCode(200)
                    .extract()
                    .asString();

            assertThat(response).contains("scoped");
        }
    }

    @Test
    void payloadsAreUntouchedWhenNoScopeIsOpen() {
        String response = api.request()
                .contentType(ContentType.JSON)
                .body("{\"amount\":100}")
                .post("/payments")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        assertThat(response).contains("default");
        server.verify(postRequestedFor(urlPathEqualTo("/payments"))
                .withRequestBody(equalToJson("{\"amount\":100}")));
    }

    @Test
    void everyRequestCarriesTheScenarioCorrelationId() {
        api.request().get("/health").then().statusCode(200);

        String correlationId = ScenarioContext.get(ScenarioKeys.CORRELATION_ID);
        server.verify(getRequestedFor(urlPathEqualTo("/health"))
                .withHeader("X-Request-Id", equalTo(correlationId)));
    }
}
