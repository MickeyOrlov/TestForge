package io.testforge.web.prewarm;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpPrewarmEngineTest {

    private HttpServer server;
    private String base;
    private final AtomicReference<String> userAgent = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            switch (exchange.getRequestURI().getPath()) {
                case "/app" -> {
                    exchange.getResponseHeaders().add("Location", "/sign-in");
                    exchange.sendResponseHeaders(302, -1);
                }
                case "/down" -> exchange.sendResponseHeaders(502, -1);
                default -> {
                    byte[] page = "<html>ok</html>".getBytes();
                    exchange.sendResponseHeaders(200, page.length);
                    exchange.getResponseBody().write(page);
                }
            }
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void eachPageIsFetchedAndItsAnswerKept() {
        PrewarmProperties properties = new PrewarmProperties(true, List.of(), true, Duration.ofSeconds(5), false,
                PrewarmProperties.Mode.HTTP);
        try (PrewarmEngine.Session session = new HttpPrewarmEngine().open(properties)) {
            PrewarmVisit home = session.visit(base + "/");
            PrewarmVisit app = session.visit(base + "/app");
            PrewarmVisit down = session.visit(base + "/down");
            PrewarmVisit closed = session.visit("http://127.0.0.1:1/");

            assertThat(home.status()).isEqualTo(200);
            assertThat(home.redirected()).isFalse();
            assertThat(app.status()).isEqualTo(200);
            assertThat(app.finalUrl()).isEqualTo(base + "/sign-in");
            assertThat(down.status()).isEqualTo(502);
            assertThat(down.reached()).isFalse();
            assertThat(closed.error()).startsWith("ConnectException");
            assertThat(userAgent.get()).isEqualTo(HttpPrewarmEngine.USER_AGENT);
        }
    }
}
