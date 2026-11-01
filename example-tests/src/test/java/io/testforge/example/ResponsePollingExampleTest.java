package io.testforge.example;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static io.testforge.http.ResponseConditions.allOf;
import static io.testforge.http.ResponseConditions.jsonPath;
import static io.testforge.http.ResponseConditions.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.restassured.response.Response;
import io.testforge.http.ApiClient;
import io.testforge.http.ResponsePoller;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * An asynchronous API — start a job, wait until it is done — without a sleep
 * or a hand-written loop. The job here moves PENDING → PENDING → DONE.
 */
@SpringBootTest(properties = "forge.wait.poll-interval=20ms")
class ResponsePollingExampleTest {

    static WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort().bindAddress("127.0.0.1"));

    static {
        server.start();
    }

    @DynamicPropertySource
    static void forgeProperties(DynamicPropertyRegistry registry) {
        registry.add("forge.http.base-url", () -> "http://localhost:" + server.port());
    }

    @Autowired
    ApiClient api;

    @Autowired
    ResponsePoller poll;

    @Test
    void waitsForTheJobToFinish() {
        server.resetAll();
        server.stubFor(get(urlPathEqualTo("/jobs/42")).inScenario("job").whenScenarioStateIs(STARTED)
                .willReturn(okJson("{\"status\":\"PENDING\"}")).willSetStateTo("second"));
        server.stubFor(get(urlPathEqualTo("/jobs/42")).inScenario("job").whenScenarioStateIs("second")
                .willReturn(okJson("{\"status\":\"PENDING\"}")).willSetStateTo("done"));
        server.stubFor(get(urlPathEqualTo("/jobs/42")).inScenario("job").whenScenarioStateIs("done")
                .willReturn(okJson("{\"status\":\"DONE\",\"result\":\"ok\"}")));

        Response done = poll.until(() -> api.request().get("/jobs/{id}", 42),
                allOf(status(200), jsonPath("status", "DONE")), Duration.ofSeconds(5));

        assertThat(done.jsonPath().getString("result")).isEqualTo("ok");
    }
}
