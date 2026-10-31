package io.testforge.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A login endpoint on a JDK HttpServer: offline, no real application. */
class SessionLoginSourceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastContentType = new AtomicReference<>();
    private volatile int status = 302;
    private volatile List<String> cookies = List.of("tracking=t1; Path=/", "SESSION=s-42; Max-Age=600; HttpOnly");

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            cookies.forEach(cookie -> exchange.getResponseHeaders().add("Set-Cookie", cookie));
            exchange.getResponseHeaders().add("Location", "/home");
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private AccessToken login(AuthProperties.SessionProperties session) {
        var role = new AuthProperties.RoleProperties(TokenType.SESSION, null, null, null, null, "alice", "pa ss",
                null, null, null, null, session);
        return new SessionLoginSource("web", role, HttpClient.newHttpClient(), Duration.ofSeconds(5),
                new MutableClock(NOW)).fetch();
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/login";
    }

    @Test
    void theNamedCookieOfARedirectingLoginBecomesTheCredential() {
        AccessToken token = login(new AuthProperties.SessionProperties(url(), null, null, null, "SESSION"));

        assertThat(token.value()).isEqualTo("SESSION=s-42");
        assertThat(token.expiresAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(lastContentType.get()).isEqualTo("application/json");
        assertThat(lastBody.get()).isEqualTo("{\"username\":\"alice\",\"password\":\"pa ss\"}");
    }

    @Test
    void withoutACookieNameTheFirstOneIsTakenAndAFormCanBeSent() {
        status = 200;
        AccessToken token = login(new AuthProperties.SessionProperties(url(),
                AuthProperties.SessionProperties.Format.FORM, "login", "secret", null));

        assertThat(token.value()).isEqualTo("tracking=t1");
        assertThat(token.expiresAt()).isNull();
        assertThat(lastBody.get()).isEqualTo("login=alice&secret=pa+ss");
    }

    @Test
    void aRejectedLoginNamesTheRoleAndStatusButNotThePassword() {
        status = 401;

        assertThatThrownBy(() -> login(new AuthProperties.SessionProperties(url(), null, null, null, null)))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("answered 401 for role 'web'")
                .hasMessageNotContaining("pa ss");
    }

    @Test
    void aLoginThatSetsNoSuchCookieSaysWhichOneWasExpected() {
        assertThatThrownBy(() -> login(new AuthProperties.SessionProperties(url(), null, null, null, "JSESSIONID")))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("set no cookie named JSESSIONID");
    }
}
