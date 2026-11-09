package io.testforge.mail;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MailpitBackendTest {
    @Test void malformedOversizedAndRedirectResponsesNeverPassOrLeakContent() throws Exception {
        var redirect = new java.util.concurrent.atomic.AtomicBoolean();
        AtomicReference<String> body = new AtomicReference<>("<secret-token>");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (redirect.get()) {
                exchange.getResponseHeaders().set("Location", "/secret-token");
                exchange.sendResponseHeaders(302, -1); exchange.close(); return;
            }
            byte[] bytes = body.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        }); server.start();
        var properties = new MailProperties(true, "http://127.0.0.1:" + server.getAddress().getPort(), null, null,
                Duration.ofMillis(500), null, null, 0, 1024);
        try (var backend = new MailpitBackend(properties)) {
            assertThatThrownBy(() -> backend.fetch("one", Duration.ofSeconds(1))).hasMessageNotContaining("secret-token");
            body.set("x".repeat(10000));
            assertThatThrownBy(() -> backend.fetch("one", Duration.ofSeconds(1))).hasMessageContaining("limit");
            redirect.set(true);
            assertThatThrownBy(() -> backend.fetch("one", Duration.ofSeconds(1)))
                    .hasMessageContaining("request failed").hasMessageNotContaining("secret-token");
            assertThatThrownBy(() -> backend.delete(List.of(), Duration.ofSeconds(1))).hasMessageContaining("unscoped");
            assertThatThrownBy(() -> backend.fetch("latest", Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> backend.attachment("one", "../other", Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        } finally { server.stop(0); }
    }
}
