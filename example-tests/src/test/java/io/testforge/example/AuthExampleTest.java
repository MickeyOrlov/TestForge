package io.testforge.example;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.unauthorized;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.testforge.auth.AsUser;
import io.testforge.auth.AuthContext;
import io.testforge.http.ApiClient;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Tests say who they act as; module-auth turns the role into a token and the
 * API client sends it. No login calls, no token plumbing in test code.
 *
 * <p>The token endpoint here is the same WireMock server, so the example runs
 * offline. A real profile points {@code token-url} at the identity provider and
 * takes {@code client-secret} from an environment variable.
 */
@SpringBootTest
class AuthExampleTest {

    static WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        server.start();
    }

    @DynamicPropertySource
    static void forgeProperties(DynamicPropertyRegistry registry) {
        String base = "http://localhost:" + server.port();
        registry.add("forge.http.base-url", () -> base);
        registry.add("forge.auth.enabled", () -> "true");
        registry.add("forge.auth.roles.admin.type", () -> "client-credentials");
        registry.add("forge.auth.roles.admin.token-url", () -> base + "/oauth/token");
        registry.add("forge.auth.roles.admin.client-id", () -> "tests-admin");
        registry.add("forge.auth.roles.admin.client-secret", () -> "example-only-secret");
        registry.add("forge.auth.roles.member.type", () -> "local-jwt");
        registry.add("forge.auth.roles.member.subject", () -> "member-1");
        registry.add("forge.auth.roles.member.claims.roles", () -> "member");
        // generated per run: a committed signing secret would be a real secret
        registry.add("forge.auth.local-jwt.secret", () -> UUID.randomUUID() + "-" + UUID.randomUUID());
    }

    @Autowired
    ApiClient api;

    @BeforeEach
    void stubs() {
        server.resetAll();
        server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/oauth/token"))
                .willReturn(okJson("{\"access_token\":\"admin-access-token\",\"expires_in\":300}")));
        server.stubFor(get(urlPathEqualTo("/accounts")).atPriority(10).willReturn(unauthorized()));
        server.stubFor(get(urlPathEqualTo("/accounts")).atPriority(1)
                .withHeader("Authorization", equalTo("Bearer admin-access-token"))
                .willReturn(okJson("{\"accounts\":3}")));
        server.stubFor(get(urlPathEqualTo("/me")).atPriority(10).willReturn(unauthorized()));
        server.stubFor(get(urlPathEqualTo("/me")).atPriority(1)
                .withHeader("Authorization", matching("Bearer [\\w-]+\\.[\\w-]+\\.[\\w-]+"))
                .willReturn(okJson("{\"id\":\"member-1\"}")));
    }

    @Test
    @AsUser("admin")
    void anAdminTestGetsTheClientCredentialsToken() {
        assertThat(api.request().get("/accounts").statusCode()).isEqualTo(200);
    }

    @Test
    @AsUser("member")
    void aMemberTestGetsALocallySignedJwt() {
        assertThat(api.request().get("/me").statusCode()).isEqualTo(200);
    }

    @Test
    void withoutARoleTheCallIsAnonymous() {
        assertThat(api.request().get("/accounts").statusCode()).isEqualTo(401);
    }

    @Test
    @AsUser("member")
    void aBlockCanSwitchRoleInsideOneTest() {
        int asAdmin = AuthContext.callAs("admin", () -> api.request().get("/accounts").statusCode());

        assertThat(asAdmin).isEqualTo(200);
        assertThat(api.request().get("/accounts").statusCode()).as("back to member").isEqualTo(401);
    }

    @Test
    @AsUser("admin")
    void anExplicitHeaderStaysUnderTheTestsControl() {
        assertThat(api.request().header("Authorization", "Bearer revoked").get("/accounts").statusCode())
                .isEqualTo(401);
    }
}
