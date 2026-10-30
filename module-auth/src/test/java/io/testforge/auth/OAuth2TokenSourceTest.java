package io.testforge.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs against a JDK HttpServer on localhost: offline, no identity provider. */
class OAuth2TokenSourceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    private HttpServer server;
    private final AtomicReference<Map<String, String>> lastForm = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body = "{\"access_token\":\"at-1\",\"token_type\":\"Bearer\",\"expires_in\":300}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastForm.set(Arrays.stream(form.split("&")).map(pair -> pair.split("=", 2)).collect(Collectors.toMap(
                    kv -> kv[0], kv -> URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8))));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
    }

    private OAuth2TokenSource source(AuthProperties.RoleProperties role) {
        return new OAuth2TokenSource("admin", role, HttpClient.newHttpClient(), Duration.ofSeconds(5),
                new MutableClock(NOW));
    }

    private AuthProperties.RoleProperties clientCredentials() {
        return new AuthProperties.RoleProperties(TokenType.CLIENT_CREDENTIALS, null, url(), "tests-admin",
                "s3cr3t&value", null, null, "admin read", null, null);
    }

    @Test
    void clientCredentialsGrantSendsTheStandardFormAndParsesExpiry() {
        AccessToken token = source(clientCredentials()).fetch();

        assertThat(token.value()).isEqualTo("at-1");
        assertThat(token.expiresAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(lastForm.get())
                .containsEntry("grant_type", "client_credentials")
                .containsEntry("client_id", "tests-admin")
                .containsEntry("client_secret", "s3cr3t&value")
                .containsEntry("scope", "admin read");
    }

    @Test
    void passwordGrantSendsTheUserCredentials() {
        source(new AuthProperties.RoleProperties(TokenType.PASSWORD, null, url(), "web", null,
                "alice", "pa ss", null, null, null)).fetch();

        assertThat(lastForm.get())
                .containsEntry("grant_type", "password")
                .containsEntry("username", "alice")
                .containsEntry("password", "pa ss")
                .doesNotContainKey("client_secret");
    }

    @Test
    void missingExpiryMeansNoExpiry() {
        body = "{\"access_token\":\"at-2\"}";

        assertThat(source(clientCredentials()).fetch().expiresAt()).isNull();
    }

    @Test
    void anErrorShowsTheOauthErrorButNeverTheSecret() {
        status = 401;
        body = "{\"error\":\"invalid_client\",\"error_description\":\"bad secret\",\"echo\":\"s3cr3t&value\"}";

        assertThatThrownBy(() -> source(clientCredentials()).fetch())
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("401")
                .hasMessageContaining("'admin'")
                .hasMessageContaining("invalid_client (bad secret)")
                .hasMessageNotContaining("s3cr3t");
    }

    @Test
    void aResponseWithoutATokenIsRejected() {
        body = "{\"token_type\":\"Bearer\"}";

        assertThatThrownBy(() -> source(clientCredentials()).fetch()).hasMessageContaining("no access_token");
    }

    @Test
    void anUnreachableEndpointNamesTheRole() {
        server.stop(0);

        assertThatThrownBy(() -> source(clientCredentials()).fetch())
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("'admin'")
                .hasMessageNotContaining("s3cr3t");
    }
}
