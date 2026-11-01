package io.testforge.example;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.testforge.core.context.ScenarioContextExtension;
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
 * GET requests have no body, so a body JSON path cannot scope them. A header
 * can: {@code forge.mock.scope-header} tells the mock where to match, and the
 * HTTP module writes the same header — configured once, like the JSON path.
 *
 * <p>Here the test calls WireMock directly to stay offline; in a real setup
 * the system under test forwards the header to its downstream calls.
 */
@SpringBootTest
@ExtendWith(ScenarioContextExtension.class)
class HeaderScopedMockTest {

    static WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort().bindAddress("127.0.0.1"));

    static {
        server.start();
    }

    @DynamicPropertySource
    static void forgeProperties(DynamicPropertyRegistry registry) {
        registry.add("forge.mock.base-url", () -> "http://localhost:" + server.port());
        registry.add("forge.mock.scope-header", () -> "X-Test-Scope");
        registry.add("forge.http.base-url", () -> "http://localhost:" + server.port());
    }

    @Autowired
    ApiClient api;

    @Autowired
    ScopedMockClient mocks;

    @BeforeEach
    void defaultStub() {
        server.resetAll();
        server.stubFor(get(urlPathEqualTo("/limits")).atPriority(10).willReturn(okJson("{\"limit\":\"default\"}")));
    }

    @Test
    void aGetRequestLandsOnItsScenariosStub() {
        try (MockScope scope = mocks.scope()) {
            scope.stub(get(urlPathEqualTo("/limits")).willReturn(okJson("{\"limit\":\"scoped\"}")));

            String body = api.request().get("/limits").then().statusCode(200).extract().asString();

            assertThat(body).contains("scoped");
            scope.verify(1, getRequestedFor(urlPathEqualTo("/limits")));
            assertThat(scope.requests().getFirst().getHeader("X-Test-Scope")).isEqualTo(scope.scopeId());
        }
    }

    @Test
    void withoutAnOpenScopeTheDefaultAnswers() {
        assertThat(api.request().get("/limits").then().extract().asString()).contains("default");
    }
}
