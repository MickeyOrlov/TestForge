package io.testforge.example;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.restassured.http.ContentType;
import io.testforge.core.context.ScenarioContextExtension;
import io.testforge.http.ApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Retry is opt-in and narrow: a gateway that answers 503 while restarting is
 * worth a second attempt, a POST that failed is not.
 */
@SpringBootTest(properties = {
        "forge.http.retry.enabled=true",
        "forge.http.retry.delay=50ms",
        "forge.http.retry.timeout=5s"
})
@ExtendWith(ScenarioContextExtension.class)
class ApiRetryExampleTest {

    static WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        server.start();
    }

    @DynamicPropertySource
    static void forgeProperties(DynamicPropertyRegistry registry) {
        registry.add("forge.http.base-url", () -> "http://localhost:" + server.port());
    }

    @Autowired
    ApiClient api;

    @BeforeEach
    void reset() {
        server.resetAll();
    }

    @Test
    void safeMethodsSurviveAnInfrastructureHiccup() {
        server.stubFor(get(urlPathEqualTo("/orders/42")).inScenario("flaky gateway")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));
        server.stubFor(get(urlPathEqualTo("/orders/42")).inScenario("flaky gateway")
                .whenScenarioStateIs("recovered")
                .willReturn(okJson("{\"id\":42}")));

        api.request().get("/orders/42").then().statusCode(200);

        server.verify(2, getRequestedFor(urlPathEqualTo("/orders/42")));
    }

    @Test
    void unsafeMethodsAreNeverRepeated() {
        server.stubFor(post(urlPathEqualTo("/orders"))
                .willReturn(aResponse().withStatus(503)));

        api.request()
                .contentType(ContentType.JSON)
                .body("{\"sku\":\"demo\"}")
                .post("/orders")
                .then()
                .statusCode(503);

        server.verify(1, postRequestedFor(urlPathEqualTo("/orders")));
    }
}
