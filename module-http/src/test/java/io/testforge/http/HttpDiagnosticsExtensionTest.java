package io.testforge.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;
import static org.junit.platform.testkit.engine.EventConditions.event;
import static org.junit.platform.testkit.engine.EventConditions.finishedWithFailure;
import static org.junit.platform.testkit.engine.EventConditions.test;
import static org.junit.platform.testkit.engine.TestExecutionResultConditions.message;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;

class HttpDiagnosticsExtensionTest {

    // one server for the class: the fixture's Spring context is cached between
    // EngineTestKit runs and keeps the port it saw first
    private static HttpServer server;

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = ("{\"path\":\"" + exchange.getRequestURI().getPath() + "\",\"token\":\"t-123\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(exchange.getRequestURI().getPath().equals("/third") ? 409 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        HttpDiagnosticsTestkitFixture.port = server.getAddress().getPort();
    }

    @BeforeEach
    void reset() {
        HttpDiagnosticsTestkitFixture.WRITTEN.clear();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private static void run(String method) {
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectMethod(HttpDiagnosticsTestkitFixture.class, method))
                .execute()
                .testEvents()
                .assertThatEvents()
                .haveExactly("failsAfterThreeCalls".equals(method) ? 1 : 0,
                        event(test(), finishedWithFailure(message(m -> m.contains("the order total")))));
    }

    @Test
    void aFailedTestPublishesItsLastExchangesRedactedAndKeepsItsOwnFailure() {
        run("failsAfterThreeCalls");

        assertThat(HttpDiagnosticsTestkitFixture.WRITTEN).singleElement().satisfies(written -> {
            assertThat(written).startsWith("module-http/HttpDiagnosticsTestkitFixture-failsAfterThreeCalls.md\n");
            assertThat(written)
                    .contains("Failure: [the order total]")
                    .contains("POST http://127.0.0.1:")
                    .contains("/second → 200")
                    .contains("/third → 409")
                    .as("only the last two are kept").doesNotContain("/first")
                    .contains("`Authorization: ***`")
                    .doesNotContain("very-secret")
                    .doesNotContain("hunter2")
                    .doesNotContain("t-123")
                    .contains("\"name\":\"x\"");
        });
    }

    @Test
    void aPassingTestPublishesNothing() {
        run("passes");

        assertThat(HttpDiagnosticsTestkitFixture.WRITTEN).isEmpty();
    }

    @Test
    void theJournalIsBoundedAndPerThread() throws Exception {
        HttpExchangeJournal journal = new HttpExchangeJournal(2);
        for (int i = 1; i <= 3; i++) {
            journal.record(new HttpExchangeJournal.Exchange("GET", "/" + i, 200, 1, Map.of(), null, null, null));
        }
        List<String> otherThread = new java.util.ArrayList<>();
        Thread thread = new Thread(() -> journal.recent().forEach(e -> otherThread.add(e.uri())));
        thread.start();
        thread.join();

        assertThat(journal.recent()).extracting(HttpExchangeJournal.Exchange::uri).containsExactly("/2", "/3");
        assertThat(otherThread).isEmpty();
        journal.clear();
        assertThat(journal.recent()).isEmpty();
    }

    @Test
    void diagnosticsDefaults() {
        assertThat(new HttpProperties.DiagnosticsProperties(null, 0))
                .extracting(HttpProperties.DiagnosticsProperties::enabled, HttpProperties.DiagnosticsProperties::last)
                .containsExactly(true, 10);
    }
}
