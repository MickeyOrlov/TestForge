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
    private final AtomicReference<String> seenQuery = new AtomicReference<>();
    private final AtomicReference<String> seenCookie = new AtomicReference<>();
    private final AtomicReference<String> seenApiKey = new AtomicReference<>();
    private final TokenProvider tokens = new TokenProvider(
            Map.of("admin", () -> new AccessToken("admin-token", null)), Clock.systemUTC(), Duration.ZERO);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seen.set(exchange.getRequestHeaders().getFirst("Authorization"));
            seenQuery.set(exchange.getRequestURI().getQuery());
            seenCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            seenApiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
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
    void theAnonymousRoleSendsNoTokenEvenWithADefaultRole() {
        AuthContext.runAs(AuthContext.ANONYMOUS, () -> send(new AuthFilter(tokens, properties("admin", null)), null));

        assertThat(seen.get()).isNull();
    }

    @Test
    void anonymousIsReportedOnlyInsideItsBlock() {
        AuthContext.runAs(AuthContext.ANONYMOUS, () -> {
            assertThat(AuthContext.anonymous()).isTrue();
            assertThat(AuthContext.currentRole()).isEmpty();
        });

        assertThat(AuthContext.anonymous()).isFalse();
    }

    @Test
    void aRoleConfiguredAsAnonymousIsRejected() {
        var props = new AuthProperties(true, null, null, null, null, null,
                Map.of(AuthContext.ANONYMOUS, new AuthProperties.RoleProperties(
                        TokenType.STATIC, "t", null, null, null, null, null, null, null, null, null, null)), null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TokenSources.from(props, null, Clock.systemUTC()))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("anonymous: reserved name");
    }

    private final TokenProvider placed = new TokenProvider(
            Map.of("integration", () -> new AccessToken("key-1", null),
                    "partner", () -> new AccessToken("key-2", null),
                    "web", () -> new AccessToken("SESSION=s-42", null)),
            Map.of("integration", CredentialPlacement.query("api_key"),
                    "partner", CredentialPlacement.header("X-API-Key", null),
                    "web", CredentialPlacement.cookie()),
            Clock.systemUTC(), Duration.ZERO);

    @Test
    void anApiKeyGoesIntoItsQueryParameter() {
        AuthContext.runAs("integration", () -> send(new AuthFilter(placed, properties(null, null)), null));

        assertThat(seenQuery.get()).isEqualTo("api_key=key-1");
        assertThat(seen.get()).isNull();
    }

    @Test
    void anApiKeyHeaderCarriesNoScheme() {
        var request = given().baseUri("http://127.0.0.1:" + server.getAddress().getPort())
                .filter(new AuthFilter(placed, properties(null, null)));
        AuthContext.runAs("partner", () -> request.get("/ping"));

        assertThat(seenApiKey.get()).isEqualTo("key-2");
        assertThat(seen.get()).isNull();
    }

    @Test
    void aSessionTravelsAsItsCookie() {
        AuthContext.runAs("web", () -> send(new AuthFilter(placed, properties(null, null)), null));

        assertThat(seenCookie.get()).isEqualTo("SESSION=s-42");
        assertThat(seen.get()).isNull();
    }

    @Test
    void aQueryParameterOrCookieTheTestSetIsNeverReplaced() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        AuthFilter filter = new AuthFilter(placed, properties(null, null));
        AuthContext.runAs("integration", () -> given().baseUri(base).filter(filter).queryParam("api_key", "revoked")
                .get("/ping"));
        assertThat(seenQuery.get()).isEqualTo("api_key=revoked");

        AuthContext.runAs("web", () -> given().baseUri(base).filter(filter).cookie("SESSION", "expired").get("/ping"));
        assertThat(seenCookie.get()).isEqualTo("SESSION=expired");
    }

    /** Reads what the auth filter left for later filters, the way module-http's recorder does. */
    private String noteFor(AuthFilter filter, java.util.function.UnaryOperator<io.restassured.specification.RequestSpecification> setup) {
        AtomicReference<Object> note = new AtomicReference<>();
        io.restassured.filter.OrderedFilter reader = new io.restassured.filter.OrderedFilter() {
            @Override
            public io.restassured.response.Response filter(io.restassured.specification.FilterableRequestSpecification req,
                    io.restassured.specification.FilterableResponseSpecification res, io.restassured.filter.FilterContext ctx) {
                note.set(ctx.getValue(io.testforge.core.context.RequestAttributes.AUTH));
                return ctx.next(req, res);
            }

            @Override
            public int getOrder() {
                return 850;
            }
        };
        setup.apply(given().baseUri("http://127.0.0.1:" + server.getAddress().getPort()).filter(filter).filter(reader))
                .get("/ping");
        return note.get() == null ? null : note.get().toString();
    }

    @Test
    void theFilterSaysWhoTheRequestActsAsAndHowNeverTheCredential() {
        AuthFilter tokenFilter = new AuthFilter(tokens, properties("admin", null));
        AuthFilter placedFilter = new AuthFilter(placed, properties(null, null));

        assertThat(AuthContext.callAs("admin", () -> noteFor(tokenFilter, r -> r)))
                .isEqualTo("admin (header Authorization)");
        assertThat(noteFor(tokenFilter, r -> r)).isEqualTo("admin (default role, header Authorization)");
        assertThat(AuthContext.callAs("admin", () -> noteFor(tokenFilter, r -> r.header("Authorization", "x"))))
                .isEqualTo("admin (header Authorization set by the test)");
        assertThat(AuthContext.callAs(AuthContext.ANONYMOUS, () -> noteFor(tokenFilter, r -> r)))
                .isEqualTo("anonymous (no credential)");
        assertThat(AuthContext.callAs("integration", () -> noteFor(placedFilter, r -> r)))
                .isEqualTo("integration (query api_key)");
        assertThat(AuthContext.callAs("web", () -> noteFor(placedFilter, r -> r)))
                .isEqualTo("web (cookie SESSION)");
        assertThat(noteFor(placedFilter, r -> r)).isNull();
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
