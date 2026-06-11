package io.testforge.example;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.testforge.core.context.ScenarioContext;
import io.testforge.core.context.ScenarioContextExtension;
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
 * Demonstrates the core idea of module-mock: parallel scenarios share one
 * WireMock server without seeing each other's stubs, because every scoped
 * stub is narrowed by a request-body matcher on the scenario id.
 */
@SpringBootTest
@ExtendWith(ScenarioContextExtension.class)
class ScopedMockIsolationTest {

    static WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        server.start();
    }

    @DynamicPropertySource
    static void forgeMockProperties(DynamicPropertyRegistry registry) {
        registry.add("forge.mock.base-url", () -> "http://localhost:" + server.port());
    }

    @Autowired
    ScopedMockClient mocks;

    @BeforeEach
    void defaultStub() {
        server.resetAll();
        // the shared low-priority default every scenario falls back to
        server.stubFor(post(urlPathEqualTo("/downstream/status"))
                .atPriority(10)
                .willReturn(okJson("{\"result\":\"default\"}")));
    }

    @Test
    void scopedStubsDoNotLeakBetweenScenarios() {
        try (MockScope scenarioA = mocks.scope("scenario-A");
             MockScope scenarioB = mocks.scope("scenario-B")) {

            scenarioA.stub(post(urlPathEqualTo("/downstream/status"))
                    .willReturn(okJson("{\"result\":\"scenario-a\"}")));
            scenarioB.stub(post(urlPathEqualTo("/downstream/status"))
                    .willReturn(okJson("{\"result\":\"scenario-b\"}")));

            assertThat(callDownstreamStatus("scenario-A")).contains("scenario-a");
            assertThat(callDownstreamStatus("scenario-B")).contains("scenario-b");
            assertThat(callDownstreamStatus("scenario-C")).contains("default");
        }

        assertThat(callDownstreamStatus("scenario-A")).contains("default");
    }

    @Test
    void generatedScopeIsPublishedToScenarioContext() {
        try (MockScope scope = mocks.scope()) {
            // payload builders elsewhere in the scenario read the id from here
            String scopeId = ScenarioContext.get(ScopedMockClient.TEST_SCOPE);
            assertThat(scope.scopeId()).isEqualTo(scopeId);

            scope.stub(post(urlPathEqualTo("/downstream/status"))
                    .willReturn(okJson("{\"result\":\"correlated\"}")));

            assertThat(callDownstreamStatus(scopeId)).contains("correlated");
        }
    }

    private String callDownstreamStatus(String scopeId) {
        return RestAssured.given()
                .baseUri("http://localhost:" + server.port())
                .contentType(ContentType.JSON)
                .body("{\"testScope\":\"%s\",\"requestId\":\"demo\"}".formatted(scopeId))
                .post("/downstream/status")
                .then()
                .statusCode(200)
                .extract()
                .asString();
    }
}
