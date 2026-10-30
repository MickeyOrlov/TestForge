package io.testforge.auth;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthFilterTest {

    private HttpServer server;
    private final AtomicReference<String> seen = new AtomicReference<>();
    private final TokenProvider tokens = new TokenProvider(
            Map.of("admin", () -> new AccessToken("admin-token", null)), Clock.systemUTC(), Duration.ZERO);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seen.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void send(AuthFilter filter, String explicitHeader) {
        var request = given().baseUri("http://127.0.0.1:" + server.getAddress().getPort()).filter(filter);
        if (explicitHeader != null) {
            request.header("Authorization", explicitHeader);
        }
        request.get("/ping");
    }

    private static AuthProperties properties(String defaultRole, String scheme) {
        return new AuthProperties(true, defaultRole, null, scheme, null, null, null, null);
    }

    @Test
    void addsTheCurrentRolesToken() {
        AuthContext.runAs("admin", () -> send(new AuthFilter(tokens, properties(null, null)), null));

        assertThat(seen.get()).isEqualTo("Bearer admin-token");
    }

    @Test
    void fallsBackToTheDefaultRole() {
        send(new AuthFilter(tokens, properties("admin", null)), null);

        assertThat(seen.get()).isEqualTo("Bearer admin-token");
    }

    @Test
    void withoutARoleTheRequestIsAnonymous() {
        send(new AuthFilter(tokens, properties(null, null)), null);

        assertThat(seen.get()).isNull();
    }

    @Test
    void anExplicitHeaderIsNeverReplaced() {
        AuthContext.runAs("admin", () -> send(new AuthFilter(tokens, properties(null, null)), "Bearer expired"));

        assertThat(seen.get()).isEqualTo("Bearer expired");
    }

    @Test
    void anEmptySchemeSendsTheBareToken() {
        AuthContext.runAs("admin", () -> send(new AuthFilter(tokens, properties(null, "")), null));

        assertThat(seen.get()).isEqualTo("admin-token");
    }

    @Test
    void runsBetweenCorrelationAndScope() {
        assertThat(AuthFilter.ORDER).isGreaterThan(100).isLessThan(200);
    }
}
