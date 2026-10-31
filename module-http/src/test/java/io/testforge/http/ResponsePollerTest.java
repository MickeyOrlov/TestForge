package io.testforge.http;

import static io.restassured.RestAssured.given;
import static io.testforge.http.ResponseConditions.allOf;
import static io.testforge.http.ResponseConditions.jsonPath;
import static io.testforge.http.ResponseConditions.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.restassured.response.Response;
import io.testforge.core.TestForgeCoreAutoConfiguration;
import io.testforge.core.wait.WaitProperties;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ResponsePollerTest {

    private HttpServer server;
    private final AtomicInteger jobCalls = new AtomicInteger();

    private final ResponsePoller poll = new ResponsePoller(
            new WaitProperties(Duration.ofSeconds(2), Duration.ofMillis(10)),
            new Redactor(new ObjectMapper(), List.of(), List.of("token")));

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jobs/1", exchange -> {
            // PENDING twice, then DONE: the shape of every asynchronous API
            String state = jobCalls.incrementAndGet() < 3 ? "PENDING" : "DONE";
            respond(exchange, 200, "{\"status\":\"" + state + "\",\"token\":\"secret-123\"}");
        });
        server.createContext("/jobs/stuck", exchange ->
                respond(exchange, 202, "{\"status\":\"PENDING\",\"token\":\"secret-123\"}"));
        server.start();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private Response get(String path) {
        return given().baseUri("http://127.0.0.1:" + server.getAddress().getPort()).get(path);
    }

    @Test
    void returnsTheFirstResponseThatMatches() {
        Response done = poll.until(() -> get("/jobs/1"), jsonPath("status", "DONE"));

        assertThat(done.jsonPath().getString("status")).isEqualTo("DONE");
        assertThat(jobCalls).hasValue(3);
    }

    @Test
    void aTimeoutNamesTheConditionAndShowsTheLastResponseRedacted() {
        assertThatThrownBy(() -> poll.until(() -> get("/jobs/stuck"),
                allOf(status(200), jsonPath("status", "DONE")), Duration.ofMillis(200)))
                .isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("Response did not match status 200 and $.status == DONE within PT0.2S")
                .hasMessageContaining("attempts)")
                .hasMessageContaining("last response: status 202")
                .hasMessageContaining("\"status\":\"PENDING\"")
                .hasMessageNotContaining("secret-123");
    }

    @Test
    void aCallThatThrowsCountsAsNotYet() {
        AtomicInteger attempts = new AtomicInteger();

        Response done = poll.until(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("connection reset");
            }
            return get("/jobs/stuck");
        }, status(202));

        assertThat(done.statusCode()).isEqualTo(202);
        assertThat(attempts).hasValue(3);
    }

    @Test
    void aTimeoutAfterOnlyErrorsSaysWhatFailed() {
        assertThatThrownBy(() -> poll.until(() -> {
            throw new IllegalStateException("connection refused");
        }, status(200), Duration.ofMillis(100)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("last call error: java.lang.IllegalStateException: connection refused")
                .hasMessageNotContaining("last response");
    }

    @Test
    void conditionsPrintWhatTheyCheck() {
        assertThat(allOf(status(200), jsonPath("items[0].id", 7),
                ResponseConditions.matching("a non-empty list", r -> true)))
                .hasToString("status 200 and $.items[0].id == 7 and a non-empty list");
    }

    @Test
    void thePollerIsAutoConfiguredWithForgeWaitDefaults() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TestForgeCoreAutoConfiguration.class,
                        TestForgeHttpAutoConfiguration.class))
                .run(context -> assertThat(context).hasSingleBean(ResponsePoller.class));
    }
}
