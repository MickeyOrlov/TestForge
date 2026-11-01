package io.testforge.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.restassured.http.ContentType;
import io.testforge.core.TestForgeCoreAutoConfiguration;
import io.testforge.core.context.ScenarioContext;
import io.testforge.core.context.ScenarioKeys;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Where the scope id goes follows where the mock looks for it. A mock that
 * matches on a header (or a query parameter, or a cookie) has no JSON path, so
 * the system under test's payloads stay as the test wrote them.
 */
class ScopeMarkerPlacementTest {

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> header = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            header.set(exchange.getRequestHeaders().getFirst("X-Test-Scope"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        ScenarioContext.put(ScenarioKeys.TEST_SCOPE, "scope-1");
    }

    @AfterEach
    void stop() {
        ScenarioContext.clear();
        server.stop(0);
    }

    private ApplicationContextRunner runner(String... properties) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TestForgeCoreAutoConfiguration.class,
                        TestForgeHttpAutoConfiguration.class))
                .withPropertyValues("forge.http.base-url=http://127.0.0.1:" + server.getAddress().getPort())
                .withPropertyValues(properties);
    }

    private void post(ApplicationContextRunner runner) {
        runner.run(context -> context.getBean(ApiClient.class).request()
                .contentType(ContentType.JSON).body("{\"amount\":100}").post("/payments")
                .then().statusCode(204));
    }

    @Test
    void aMockMatchingOnTheBodyGetsTheScopeInTheBody() {
        post(runner());

        assertThat(body.get()).contains("\"testScope\":\"scope-1\"");
    }

    @Test
    void aMockMatchingOnAHeaderGetsTheHeaderAndTheBodyIsLeftAlone() {
        post(runner("forge.mock.scope-header=X-Test-Scope"));

        assertThat(header.get()).isEqualTo("scope-1");
        assertThat(body.get()).isEqualTo("{\"amount\":100}");
    }

    @Test
    void aMockMatchingOnAQueryParameterLeavesTheBodyAlone() {
        post(runner("forge.mock.scope-query-param=scope"));

        assertThat(body.get()).isEqualTo("{\"amount\":100}");
    }

    @Test
    void anExplicitJsonPathStillWritesTheBody() {
        post(runner("forge.mock.scope-header=X-Test-Scope", "forge.http.scope.json-path=$.meta.scope"));

        assertThat(header.get()).isEqualTo("scope-1");
        assertThat(body.get()).contains("\"scope\":\"scope-1\"");
    }
}
