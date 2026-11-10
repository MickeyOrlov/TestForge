package io.testforge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

class SecurityNucleiTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TEMPLATE = DockerNucleiEngine.TEMPLATE;

    @Test
    void enabledButUnexecutedNucleiKeepsSecurityIncomplete(@TempDir Path output) {
        var context =
                new ApplicationContextRunner()
                        .withConfiguration(
                                AutoConfigurations.of(TestForgeSecurityAutoConfiguration.class));
        context.run(c -> assertThat(c).doesNotHaveBean(SecurityNucleiRunner.class));
        context.withPropertyValues(
                        "forge.security.nuclei.enabled=true", "forge.security.output-dir=" + output)
                .run(
                        c -> {
                            assertThatThrownBy(
                                            () ->
                                                    c.getBean(SecurityNucleiRunner.class)
                                                            .scanPages(
                                                                    List.of("/sample"),
                                                                    "http://127.0.0.1"))
                                    .hasMessageContaining("test-environment");
                            assertThat(c.getBean(SecurityRunner.class).run().coverage())
                                    .anyMatch(
                                            row ->
                                                    row.check().equals("block-nuclei")
                                                            && row.status().equals("not_measured"));
                        });
    }

    @Test
    void theGatewayStripsCredentialsAndRefusesWritesQueriesAndRedirects() throws Exception {
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new CopyOnWriteArrayList<String>();
        upstream.createContext(
                "/",
                exchange -> {
                    requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                    String credential = exchange.getRequestHeaders().getFirst("X-Custom-Token");
                    if (credential != null) requests.add(credential);
                    if (exchange.getRequestURI().getPath().equals("/redirect")) {
                        exchange.getResponseHeaders().set("Location", "/outside");
                        exchange.sendResponseHeaders(302, -1);
                    } else {
                        exchange.getResponseHeaders()
                                .set("Set-Cookie", "fixture-secret=do-not-forward");
                        exchange.sendResponseHeaders(200, -1);
                    }
                    exchange.close();
                });
        upstream.start();
        try {
            var collector = new SecurityCollector(100);
            var engine =
                    (NucleiEngine)
                            (dir, targets, properties) -> {
                                URI sample = loopback(targets.get(0));
                                URI redirect = loopback(targets.get(1));
                                try (var client = HttpClient.newHttpClient()) {
                                    var read = exchange(client, sample, "GET");
                                    assertThat(read.statusCode()).isEqualTo(200);
                                    assertThat(read.headers().firstValue("Set-Cookie")).isEmpty();
                                    assertThat(exchange(client, sample, "POST").statusCode())
                                            .isEqualTo(403);
                                    assertThat(
                                                    exchange(
                                                                    client,
                                                                    URI.create(sample + "?q=x"),
                                                                    "GET")
                                                            .statusCode())
                                            .isEqualTo(403);
                                    var redirected = exchange(client, redirect, "GET");
                                    assertThat(redirected.statusCode()).isEqualTo(403);
                                    assertThat(redirected.headers().firstValue("Location"))
                                            .isEmpty();
                                } catch (Exception e) {
                                    throw new AssertionError(e);
                                }
                                return new NucleiEngine.Result(
                                        NucleiEngine.Phase.COMPLETE,
                                        new ObjectMapper().createArrayNode());
                            };
            int port = upstream.getAddress().getPort();
            var properties =
                    new SecurityNucleiProperties(
                            true, true, List.of("127.0.0.1:" + port), null, null, null);
            var runner = new SecurityNucleiRunner(properties, collector, engine);
            String origin = "http://127.0.0.1:" + port;
            assertThatThrownBy(() -> runner.scanPages(List.of("/a/%2e"), origin))
                    .isInstanceOf(IllegalArgumentException.class);
            for (String target : List.of(origin + "/a/../b", origin + "/api?x=1"))
                assertThatThrownBy(() -> runner.scanPages(List.of("/sample"), target))
                        .isInstanceOf(IllegalArgumentException.class);
            runner.scanPages(List.of("/sample", "/redirect"), origin);
            assertThat(requests).containsExactly("GET /sample", "GET /redirect");
            assertThat(collector.coverage())
                    .hasSize(2)
                    .allMatch(row -> row.status().equals("not_measured"));
            assertThat(collector.findings()).isEmpty();
        } finally {
            upstream.stop(0);
        }
    }

    // the JSONL shape Nuclei 3.11.1 writes with -jsonl -omit-raw -omit-template: one row per match
    private static ObjectNode match(
            String matchedAt, String matcher, String template, String severity) {
        var row = MAPPER.createObjectNode();
        row.put("template-id", template);
        row.put("type", "http");
        row.put("matched-at", matchedAt);
        row.put("matcher-name", matcher);
        row.putObject("info").put("severity", severity);
        return row;
    }

    /** An engine that sends one GET per target through the gateway, then returns {@code result}. */
    private static NucleiEngine visiting(Function<List<String>, NucleiEngine.Result> result) {
        return (dir, targets, properties) -> {
            try (var client = HttpClient.newHttpClient()) {
                for (String target : targets) exchange(client, loopback(target), "GET");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            return result.apply(targets);
        };
    }

    private static HttpServer pages(List<String> requests, int status) throws Exception {
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext(
                "/",
                exchange -> {
                    requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                    exchange.sendResponseHeaders(status, -1);
                    exchange.close();
                });
        upstream.start();
        return upstream;
    }

    @Test
    void matchesBecomeInfoFindingsOnTheConfiguredPageBelowABasePath() throws Exception {
        var requests = new CopyOnWriteArrayList<String>();
        var upstream = pages(requests, 200);
        try {
            int port = upstream.getAddress().getPort();
            var collector = new SecurityCollector(100);
            var engine =
                    visiting(
                            targets -> {
                                var rows = MAPPER.createArrayNode();
                                rows.add(match(targets.get(0), "content-security-policy",
                                        DockerNucleiEngine.TEMPLATE, "info"));
                                rows.add(match(targets.get(0), "x-frame-options",
                                        DockerNucleiEngine.TEMPLATE, "info"));
                                return new NucleiEngine.Result(NucleiEngine.Phase.COMPLETE, rows);
                            });
            new SecurityNucleiRunner(
                            new SecurityNucleiProperties(
                                    true, true, List.of("127.0.0.1:" + port), null, null, null),
                            collector,
                            engine)
                    .scanPages(List.of("/sample"), "http://127.0.0.1:" + port + "/api");

            assertThat(requests)
                    .as("the base path reaches the service")
                    .containsExactly("GET /api/sample");
            assertThat(collector.coverage())
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.target()).isEqualTo("GET /sample");
                        assertThat(row.status()).isEqualTo("measured");
                    });
            assertThat(collector.findings())
                    .extracting(f -> f.target() + " " + f.severity())
                    .containsExactlyInAnyOrder(
                            "GET /sample content-security-policy INFO",
                            "GET /sample x-frame-options INFO");
        } finally {
            upstream.stop(0);
        }
    }

    @Test
    void anUnexpectedRowAFailedPhaseOrANon2xxPageAddsNoFindingAndStaysNotMeasured()
            throws Exception {
        List<Function<List<String>, NucleiEngine.Result>> outcomes =
                List.of(
                        t -> rows(match(t.get(0), "server-banner", TEMPLATE, "info")),
                        t -> rows(match(t.get(0), "x-frame-options", "other-template", "info")),
                        t -> rows(match(t.get(0), "x-frame-options", TEMPLATE, "high")),
                        t -> rows(match("http://elsewhere.test/sample", "x-frame-options",
                                DockerNucleiEngine.TEMPLATE, "info")),
                        t -> new NucleiEngine.Result(NucleiEngine.Phase.TIMEOUT, null),
                        t -> new NucleiEngine.Result(NucleiEngine.Phase.REPORT, null),
                        t -> new NucleiEngine.Result(NucleiEngine.Phase.CLEANUP, null),
                        t -> null);
        var requests = new CopyOnWriteArrayList<String>();
        var upstream = pages(requests, 200);
        var missing = pages(requests, 404);
        try {
            for (var outcome : outcomes)
                assertNotMeasured(upstream, visiting(outcome));
            assertNotMeasured(
                    missing,
                    visiting(t -> new NucleiEngine.Result(
                            NucleiEngine.Phase.COMPLETE, MAPPER.createArrayNode())));
        } finally {
            upstream.stop(0);
            missing.stop(0);
        }
    }

    private static NucleiEngine.Result rows(ObjectNode row) {
        return new NucleiEngine.Result(
                NucleiEngine.Phase.COMPLETE, MAPPER.createArrayNode().add(row));
    }

    private static void assertNotMeasured(HttpServer upstream, NucleiEngine engine) {
        int port = upstream.getAddress().getPort();
        var collector = new SecurityCollector(100);
        new SecurityNucleiRunner(
                        new SecurityNucleiProperties(
                                true, true, List.of("127.0.0.1:" + port), null, null, null),
                        collector,
                        engine)
                .scanPages(List.of("/sample"), "http://127.0.0.1:" + port);
        assertThat(collector.findings()).isEmpty();
        assertThat(collector.coverage())
                .singleElement()
                .satisfies(row -> assertThat(row.status()).isEqualTo("not_measured"));
    }

    private static URI loopback(String url) {
        URI uri = URI.create(url);
        return URI.create("http://127.0.0.1:" + uri.getPort() + uri.getRawPath());
    }

    private static HttpResponse<Void> exchange(HttpClient client, URI uri, String method)
            throws Exception {
        var request =
                HttpRequest.newBuilder(uri)
                        .header("X-Custom-Token", "fixture-private")
                        .method(method, HttpRequest.BodyPublishers.noBody())
                        .build();
        return client.send(request, HttpResponse.BodyHandlers.discarding());
    }
}
