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
import io.testforge.data.TemplateRenderer;
import io.testforge.mock.MockScope;
import io.testforge.mock.ScopedMockClient;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The full correlation loop in one example: the mock client generates a scope
 * id and publishes it to the scenario context, the payload template embeds it
 * into an outgoing HTTP request, and the request lands on the scenario's own
 * stub of the shared mock server.
 */
@SpringBootTest
@ExtendWith(ScenarioContextExtension.class)
class ScopedRequestTemplateTest {

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

    @Autowired
    TemplateRenderer templates;

    @Test
    void payloadTemplateEmbedsScopeFromScenarioContext() {
        try (MockScope scope = mocks.scope()) {
            scope.stub(post(urlPathEqualTo("/payments"))
                    .willReturn(okJson("{\"state\":\"created\"}")));

            String body = templates.render(
                    "{\"testScope\":\"%{scope}%\",\"amount\":100}",
                    Map.of("scope", ScenarioContext.get(ScopedMockClient.TEST_SCOPE)));

            String response = RestAssured.given()
                    .baseUri("http://localhost:" + server.port())
                    .contentType(ContentType.JSON)
                    .body(body)
                    .post("/payments")
                    .then()
                    .statusCode(200)
                    .extract()
                    .asString();

            assertThat(response).contains("created");
        }
    }
}
