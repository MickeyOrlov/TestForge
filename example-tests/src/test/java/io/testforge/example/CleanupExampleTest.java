package io.testforge.example;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.testforge.data.cleanup.Cleanup;
import io.testforge.data.cleanup.CleanupExtension;
import io.testforge.http.ApiClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Tests on a shared environment undo what they create: the delete is
 * registered the moment the order exists and runs after the test, even when
 * the test fails halfway.
 */
@SpringBootTest
@ExtendWith(CleanupExtension.class)
class CleanupExampleTest {

    static WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort().bindAddress("127.0.0.1"));

    static {
        server.start();
        server.stubFor(post(urlPathEqualTo("/api/v1/orders")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody("{\"id\":\"o-1\"}")));
        server.stubFor(delete(urlPathMatching("/api/v1/orders/.+")).willReturn(aResponse().withStatus(204)));
    }

    @DynamicPropertySource
    static void forgeProperties(DynamicPropertyRegistry registry) {
        registry.add("forge.http.base-url", () -> "http://localhost:" + server.port());
    }

    @AfterAll
    static void everyOrderWasDeleted() {
        // per-test cleanups have already run by the time @AfterAll does
        assertThat(server.findAll(deleteRequestedFor(urlPathEqualTo("/api/v1/orders/o-1")))).hasSize(2);
    }

    @Autowired
    ApiClient api;

    private String createOrder() {
        String id = api.request().contentType("application/json").body("{\"item\":\"book\"}")
                .post("/api/v1/orders").path("id");
        Cleanup.register("order " + id, () -> api.request().delete("/api/v1/orders/{id}", id).then().statusCode(204));
        return id;
    }

    @Test
    void anOrderCanBeCreated() {
        assertThat(createOrder()).isNotBlank();
    }

    @Test
    void anotherOrderCanBeCreated() {
        assertThat(createOrder()).isNotBlank();
    }
}
