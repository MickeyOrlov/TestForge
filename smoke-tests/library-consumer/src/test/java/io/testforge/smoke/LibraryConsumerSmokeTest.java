package io.testforge.smoke;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.testforge.core.context.ScenarioContext;
import io.testforge.core.context.ScenarioContextExtension;
import io.testforge.core.context.ScenarioKeys;
import io.testforge.http.ApiClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = SmokeTestApplication.class)
@ExtendWith(ScenarioContextExtension.class)
class LibraryConsumerSmokeTest {

    private static final WireMockServer SERVER =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        SERVER.start();
    }

    @DynamicPropertySource
    static void testForgeProperties(DynamicPropertyRegistry registry) {
        registry.add("forge.http.base-url", () -> "http://localhost:" + SERVER.port());
    }

    @Autowired
    ApiClient api;

    @BeforeEach
    void stubApi() {
        SERVER.resetAll();
        SERVER.stubFor(get(urlPathEqualTo("/health"))
                .willReturn(okJson("{\"status\":\"UP\"}")));
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop();
    }

    @Test
    void loadsAutoConfigurationFromPublishedJarsAndCallsApi() {
        String status = api.request()
                .get("/health")
                .then()
                .statusCode(200)
                .extract()
                .path("status");

        assertThat(status).isEqualTo("UP");
        String correlationId = ScenarioContext.get(ScenarioKeys.CORRELATION_ID);
        SERVER.verify(getRequestedFor(urlPathEqualTo("/health"))
                .withHeader("X-Request-Id", equalTo(correlationId)));
    }
}
