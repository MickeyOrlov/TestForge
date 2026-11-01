package io.testforge.smoke;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.unauthorized;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.testforge.auth.AsUser;
import io.testforge.http.ApiClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * module-auth from published jars: REST Assured is only compileOnly there, so
 * this proves its filter is wired against the REST Assured module-http brings.
 */
@SpringBootTest(classes = SmokeTestApplication.class)
class PublishedAuthSmokeTest {

    static WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort().bindAddress("127.0.0.1"));

    static {
        server.start();
        server.stubFor(get(urlPathEqualTo("/me")).atPriority(10).willReturn(unauthorized()));
        server.stubFor(get(urlPathEqualTo("/me")).atPriority(1)
                .withHeader("Authorization", equalTo("Bearer smoke-token"))
                .willReturn(okJson("{\"role\":\"partner\"}")));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("forge.http.base-url", () -> "http://localhost:" + server.port());
        registry.add("forge.auth.enabled", () -> "true");
        registry.add("forge.auth.roles.partner.type", () -> "static");
        registry.add("forge.auth.roles.partner.token", () -> "smoke-token");
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @Autowired
    ApiClient api;

    @Test
    @AsUser("partner")
    void thePublishedAuthModuleSignsApiClientRequests() {
        assertThat(api.request().get("/me").statusCode()).isEqualTo(200);
    }

    @Test
    void withoutARoleTheRequestIsAnonymous() {
        assertThat(api.request().get("/me").statusCode()).isEqualTo(401);
    }
}
