package io.testforge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class SecurityActiveTest {
    HttpServer server;
    final AtomicInteger traffic = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                e -> {
                    traffic.incrementAndGet();
                    byte[] body = "{\"ok\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    e.getResponseHeaders().add("Content-Type", "application/json");
                    e.sendResponseHeaders(200, body.length);
                    e.getResponseBody().write(body);
                    e.close();
                });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    String target() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    static OpenAPI document() {
        return new OpenAPI()
                .openapi("3.0.3")
                .info(new Info().title("Synthetic scanner fixture").version("1"))
                .paths(
                        new Paths()
                                .addPathItem(
                                        "/items",
                                        new PathItem()
                                                .get(
                                                        new Operation()
                                                                .operationId("items")
                                                                .responses(
                                                                        new ApiResponses()
                                                                                .addApiResponse(
                                                                                        "200",
                                                                                        new ApiResponse()
                                                                                                .description(
                                                                                                        "items"))))
                                                .post(
                                                        new Operation()
                                                                .operationId("mustNotWrite"))));
    }

    static SecurityActiveProperties properties(Integer requests) {
        return new SecurityActiveProperties(
                true,
                true,
                List.of("127.0.0.1"),
                10,
                requests,
                java.time.Duration.ofSeconds(30),
                null,
                null,
                null,
                null,
                null);
    }

    static String scannerBase(Path dir) throws Exception {
        return new ObjectMapper()
                .readTree(dir.resolve("openapi.json").toFile())
                .path("servers")
                .get(0)
                .path("url")
                .asText();
    }

    static int send(String base, String method, String path) throws Exception {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
                .send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                base.replace("host.docker.internal", "127.0.0.1")
                                                        + path))
                                .method(method, HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    @Test
    void gatewayInjectsRoleCredentialsWithoutWritingThemIntoScannerInput() {
        String secret = "synthetic-gateway-credential";
        var received = new AtomicReference<String>();
        server.removeContext("/");
        server.createContext(
                "/",
                e -> {
                    traffic.incrementAndGet();
                    received.set(e.getRequestHeaders().getFirst("X-Scan-Key"));
                    byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    e.sendResponseHeaders(200, body.length);
                    e.getResponseBody().write(body);
                    e.close();
                });
        var tokens =
                new io.testforge.auth.TokenProvider(
                        Map.of("scanner", () -> new io.testforge.auth.AccessToken(secret, null)),
                        Map.of(
                                "scanner",
                                io.testforge.auth.CredentialPlacement.header("X-Scan-Key", "")),
                        java.time.Clock.systemUTC(),
                        java.time.Duration.ofSeconds(30));
        var auth =
                new io.testforge.auth.AuthProperties(
                        true, null, null, null, null, null, Map.of(), null);
        var p =
                new SecurityActiveProperties(
                        true,
                        true,
                        List.of("127.0.0.1"),
                        10,
                        10,
                        java.time.Duration.ofSeconds(30),
                        null,
                        null,
                        "scanner",
                        null,
                        null);
        ZapEngine engine =
                (dir, properties) -> {
                    try {
                        assertThat(
                                        Files.readString(dir.resolve("plan.json"))
                                                + Files.readString(dir.resolve("openapi.json")))
                                .doesNotContain(secret);
                        assertThat(send(scannerBase(dir), "GET", "/items")).isEqualTo(200);
                        return new ZapEngine.Result(
                                true, new ObjectMapper().valueToTree(Map.of("site", List.of())));
                    } catch (Exception e) {
                        return new ZapEngine.Result(false, null);
                    }
                };
        var collector = new SecurityCollector(100);
        new SecurityActiveRunner(p, collector, engine, tokens, auth).scan(document(), target());
        assertThat(received).hasValue(secret);
        assertThat(collector.findings().toString() + collector.coverage()).doesNotContain(secret);
    }

    @Test
    void gatewayEnforcesItsRateBudget() {
        var p =
                new SecurityActiveProperties(
                        true,
                        true,
                        List.of("127.0.0.1"),
                        1,
                        10,
                        java.time.Duration.ofSeconds(30),
                        null,
                        null,
                        null,
                        null,
                        null);
        ZapEngine engine =
                (dir, properties) -> {
                    try {
                        String base = scannerBase(dir);
                        long started = System.nanoTime();
                        assertThat(send(base, "GET", "/items")).isEqualTo(200);
                        assertThat(send(base, "GET", "/items")).isEqualTo(200);
                        assertThat(System.nanoTime() - started)
                                .isGreaterThanOrEqualTo(java.time.Duration.ofMillis(900).toNanos());
                        return new ZapEngine.Result(
                                true, new ObjectMapper().valueToTree(Map.of("site", List.of())));
                    } catch (Exception e) {
                        return new ZapEngine.Result(false, null);
                    }
                };
        new SecurityActiveRunner(p, new SecurityCollector(100), engine, null, null)
                .scan(document(), target());
        assertThat(traffic).hasValue(2);
    }

    @Test
    void zapResolutionRequiresTheSameCompletePinnedPolicy(
            @org.junit.jupiter.api.io.TempDir Path dir) {
        var leaked = new AtomicBoolean(true);
        ZapEngine engine =
                (privateDir, p) -> {
                    try {
                        String base = scannerBase(privateDir);
                        assertThat(send(base, "GET", "/items")).isEqualTo(200);
                        var alerts =
                                leaked.get()
                                        ? List.of(
                                                Map.of(
                                                        "pluginid",
                                                        "40012",
                                                        "riskcode",
                                                        "3",
                                                        "instances",
                                                        List.of(Map.of("uri", base + "/items"))))
                                        : List.of();
                        return new ZapEngine.Result(
                                true,
                                new ObjectMapper()
                                        .valueToTree(
                                                Map.of("site", List.of(Map.of("alerts", alerts)))));
                    } catch (Exception e) {
                        return new ZapEngine.Result(false, null);
                    }
                };
        var reports =
                new SecurityProperties(
                        null,
                        dir.resolve("reports").toString(),
                        dir.resolve("baseline.json").toString(),
                        null,
                        null,
                        null);
        var first = new SecurityCollector(100);
        new SecurityActiveRunner(properties(10), first, engine, null, null)
                .scan(document(), target());
        new SecurityRunner(reports, first, io.testforge.artifact.ArtifactSink.NO_OP, () -> true)
                .writeBaseline();
        leaked.set(false);
        var second = new SecurityCollector(100);
        new SecurityActiveRunner(properties(10), second, engine, null, null)
                .scan(document(), target());
        var fixed =
                new SecurityRunner(
                                reports,
                                second,
                                io.testforge.artifact.ArtifactSink.NO_OP,
                                () -> true)
                        .assertAcceptable();
        assertThat(fixed.findings())
                .singleElement()
                .extracting(SecurityFinding::status)
                .isEqualTo("resolved");
        var changed = new SecurityCollector(100);
        var baselineMode =
                new SecurityActiveProperties(
                        true,
                        true,
                        List.of("127.0.0.1"),
                        10,
                        10,
                        java.time.Duration.ofSeconds(30),
                        null,
                        null,
                        null,
                        SecurityActiveProperties.Mode.BASELINE,
                        null);
        new SecurityActiveRunner(baselineMode, changed, engine, null, null)
                .scan(document(), target());
        var incomparable =
                new SecurityRunner(
                                reports,
                                changed,
                                io.testforge.artifact.ArtifactSink.NO_OP,
                                () -> true)
                        .run();
        assertThat(incomparable.complete()).isFalse();
        assertThat(incomparable.findings()).noneMatch(f -> f.status().equals("resolved"));
    }

    @Test
    void sanitizedDocumentGatewayAndReportsNeverExposeRawScannerValues() throws Exception {
        var collector = new SecurityCollector(100);
        var temporary = new AtomicReference<Path>();
        String raw = "synthetic-scanner-credential";
        ZapEngine fake =
                (dir, p) -> {
                    temporary.set(dir);
                    try {
                        String base = scannerBase(dir);
                        var doc = new ObjectMapper().readTree(dir.resolve("openapi.json").toFile());
                        assertThat(doc.path("paths").path("/items").has("post")).isFalse();
                        assertThat(send(base, "GET", "/items")).isEqualTo(200);
                        var data =
                                Map.of(
                                        "site",
                                        List.of(
                                                Map.of(
                                                        "alerts",
                                                        List.of(
                                                                Map.of(
                                                                        "pluginid",
                                                                        "40012",
                                                                        "riskcode",
                                                                        "3",
                                                                        "evidence",
                                                                        raw,
                                                                        "instances",
                                                                        List.of(
                                                                                Map.of(
                                                                                        "uri",
                                                                                        base
                                                                                                + "/items?secret="
                                                                                                + raw)))))));
                        return new ZapEngine.Result(true, new ObjectMapper().valueToTree(data));
                    } catch (Exception e) {
                        throw new IllegalStateException("synthetic engine fixture failed");
                    }
                };
        new SecurityActiveRunner(properties(20), collector, fake, null, null)
                .scan(document(), target());
        assertThat(traffic).hasValue(1);
        assertThat(collector.findings())
                .singleElement()
                .extracting(SecurityFinding::check)
                .isEqualTo("zap-40012");
        assertThat(collector.findings().toString() + collector.coverage())
                .doesNotContain(raw, "security-scope-");
        assertThat(temporary.get()).doesNotExist();
    }

    @Test
    void outOfScopeHostsWritesPathsAndBudgetsAreRefusedBeforeTraffic() {
        var collector = new SecurityCollector(100);
        var calls = new AtomicInteger();
        ZapEngine fake =
                (dir, p) -> {
                    calls.incrementAndGet();
                    try {
                        String base = scannerBase(dir);
                        assertThat(send(base, "POST", "/items")).isEqualTo(403);
                        assertThat(send(base, "GET", "/unlisted")).isEqualTo(403);
                        assertThat(send(base, "GET", "/items")).isEqualTo(200);
                        assertThat(send(base, "GET", "/items")).isEqualTo(429);
                        return new ZapEngine.Result(
                                true, new ObjectMapper().valueToTree(Map.of("site", List.of())));
                    } catch (Exception e) {
                        return new ZapEngine.Result(false, null);
                    }
                };
        var runner = new SecurityActiveRunner(properties(1), collector, fake, null, null);
        assertThatThrownBy(() -> runner.scan(document(), "http://unapproved.example.test"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(0);
        runner.scan(document(), target());
        assertThat(traffic).hasValue(1);
        assertThat(collector.coverage()).anyMatch(c -> c.status().equals("not_measured"));
        assertThatThrownBy(
                        () ->
                                new SecurityActiveProperties(
                                        true,
                                        true,
                                        List.of("localhost"),
                                        null,
                                        null,
                                        null,
                                        Set.of(40018),
                                        null,
                                        null,
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void remoteReferencesAndFailedScannerCannotProduceACleanResult() {
        var broken = document();
        broken.getPaths()
                .get("/items")
                .getGet()
                .setResponses(
                        new ApiResponses()
                                .addApiResponse(
                                        "200",
                                        new ApiResponse()
                                                .$ref(
                                                        "https://unapproved.example.test/response.json")));
        var calls = new AtomicInteger();
        ZapEngine failed =
                (dir, p) -> {
                    calls.incrementAndGet();
                    return new ZapEngine.Result(false, null);
                };
        var collector = new SecurityCollector(100);
        var runner = new SecurityActiveRunner(properties(10), collector, failed, null, null);
        assertThatThrownBy(() -> runner.scan(broken, target()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(0);
        runner.scan(document(), target());
        assertThat(collector.coverage()).anyMatch(c -> c.status().equals("not_measured"));
        assertThat(traffic).hasValue(0);
    }

    @Test
    void crossOriginRedirectIsRemovedAtTheGateway() throws Exception {
        server.removeContext("/");
        server.createContext(
                "/",
                e -> {
                    traffic.incrementAndGet();
                    e.getResponseHeaders()
                            .add("Location", "https://unapproved.example.test/private");
                    e.sendResponseHeaders(302, -1);
                    e.close();
                });
        var collector = new SecurityCollector(100);
        ZapEngine fake =
                (dir, p) -> {
                    try {
                        assertThat(send(scannerBase(dir), "GET", "/items")).isEqualTo(403);
                        return new ZapEngine.Result(
                                true, new ObjectMapper().valueToTree(Map.of("site", List.of())));
                    } catch (Exception e) {
                        return new ZapEngine.Result(false, null);
                    }
                };
        new SecurityActiveRunner(properties(10), collector, fake, null, null)
                .scan(document(), target());
        assertThat(traffic).hasValue(1);
        assertThat(collector.coverage()).anyMatch(c -> c.status().equals("not_measured"));
    }
}
