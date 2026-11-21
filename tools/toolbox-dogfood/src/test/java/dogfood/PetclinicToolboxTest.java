package dogfood;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import io.testforge.a11y.*;
import io.testforge.faults.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;

class PetclinicToolboxTest {
    @Test void realPetclinicRunsThroughToxiproxyAndAxeWithoutSourceChanges() throws Exception {
        Path output = Path.of(System.getProperty("dogfood.output")); Files.createDirectories(output);
        int port;
        try (var socket = new java.net.ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
        try (Network network = Network.newNetwork();
             var db = new PostgreSQLContainer("postgres:17.4-alpine").withDatabaseName("petclinic")
                     .withNetwork(network).withNetworkAliases("db")) {
            db.start();
            var props = new FaultsProperties(true, null, null, network.getId(), false, 0, 0,
                    Map.of("petclinic-db", "db:5432"), List.of("db:5432"));
            try (var faults = new Faults(props, new ToxiproxyBackend(props), null); var scope = faults.on("petclinic-db")) {
                var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-jar",
                        System.getProperty("petclinic.jar")).redirectErrorStream(true).redirectOutput(output.resolve("application-private.log").toFile());
                Map<String, String> inherited = new HashMap<>(builder.environment()); builder.environment().clear();
                for (String key : List.of("PATH", "HOME", "TMPDIR", "LANG")) if (inherited.containsKey(key)) builder.environment().put(key, inherited.get(key));
                builder.environment().putAll(Map.of("SPRING_PROFILES_ACTIVE", "postgres", "SERVER_PORT", String.valueOf(port),
                        "SERVER_ADDRESS", "127.0.0.1", "SPRING_DATASOURCE_URL", "jdbc:postgresql://" + scope.endpoint().address()
                            + "/petclinic?socketTimeout=1&connectTimeout=1", "SPRING_DATASOURCE_USERNAME", db.getUsername(),
                        "SPRING_DATASOURCE_PASSWORD", db.getPassword(), "SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT", "1500",
                        "SPRING_DATASOURCE_HIKARI_VALIDATION_TIMEOUT", "1000", "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", "2"));
                Process app = builder.start();
                try {
                    var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
                    String origin = "http://127.0.0.1:" + port;
                    await().atMost(Duration.ofSeconds(90)).until(() -> {
                        if (!app.isAlive()) throw new AssertionError("Petclinic exited; inspect local application log");
                        try { return get(http, origin + "/owners?lastName=").statusCode() == 200; }
                        catch (Exception e) { return false; }
                    });
                    Map<String, Object> evidence = new LinkedHashMap<>();
                    evidence.put("application", "spring-projects/spring-petclinic"); evidence.put("unchangedJar", true);
                    evidence.put("healthyOwnersStatus", get(http, origin + "/owners?lastName=").statusCode());
                    try (Playwright playwright = Playwright.create(); Browser browser = playwright.chromium().launch()) {
                        try (BrowserContext context = browser.newContext()) {
                            Page page = context.newPage(); page.navigate(origin + "/owners/find");
                            page.locator("form").first().waitFor();
                            var policy = new A11yProperties(true, null, null, "serious", output.resolve("baseline.json").toString(),
                                    output.resolve("a11y").toString(), List.of("/owners/find"), null, null);
                            var a11y = new A11y(policy, new AxeEngine(), null);
                            var scan = a11y.check(page); evidence.put("a11yRules", scan.violations().stream().map(v -> v.rule()).distinct().toList());
                            evidence.put("a11yViolations", scan.violations().size()); evidence.put("a11yManualReview", scan.incomplete().size());
                            evidence.put("axeVersion", scan.engineVersion());
                            assertThat(scan.incomplete()).isEmpty();
                            if (scan.incomplete().isEmpty()) {
                                a11y.writeBaseline(scan);
                                assertThat(a11y.assertNoNewViolations(page).passed()).isTrue();
                                page.locator("body").evaluate("body => { const img=document.createElement('img'); img.id='planted-missing-alt'; img.src='data:image/gif;base64,R0lGODlhAQABAAAAACw='; body.appendChild(img); }");
                                assertThatThrownBy(() -> a11y.assertNoNewViolations(page)).isInstanceOf(AssertionError.class);
                                evidence.put("newViolationBlocked", true);
                            } else evidence.put("adoptionBlockedByManualReview", true);
                        }
                    }
                    scope.latency(Duration.ofSeconds(2));
                    long started = System.nanoTime(); var failed = get(http, origin + "/owners?lastName=");
                    long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
                    assertThat(failed.statusCode()).isGreaterThanOrEqualTo(500); assertThat(elapsed).isLessThan(5000);
                    evidence.put("faultStatus", failed.statusCode()); evidence.put("faultDurationMillis", elapsed);
                    scope.restore();
                    await().atMost(Duration.ofSeconds(20)).until(() -> {
                        try { return get(http, origin + "/owners?lastName=").statusCode() == 200; }
                        catch (Exception e) { return false; }
                    });
                    evidence.put("recoveredStatus", get(http, origin + "/owners?lastName=").statusCode());
                    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.resolve("summary.json").toFile(), evidence);
                } finally {
                    app.destroy(); if (!app.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) { app.destroyForcibly(); app.waitFor(5, java.util.concurrent.TimeUnit.SECONDS); }
                }
            }
        }
    }
    private HttpResponse<String> get(HttpClient client, String uri) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
