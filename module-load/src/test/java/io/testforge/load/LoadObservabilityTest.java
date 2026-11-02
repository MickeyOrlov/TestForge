package io.testforge.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Opt-in end-to-end: native k6 → Prometheus remote write → provisioned Grafana dashboard. */
@Tag("load-observability")
class LoadObservabilityTest {
    @TempDir Path dir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Test void nativeMetricsReachPrometheusAndEveryDashboardQueryWorks() throws Exception {
        String prometheus = System.getProperty("testforge.prometheus");
        String grafana = System.getProperty("testforge.grafana");
        assertThat(get(prometheus + "/-/ready").statusCode()).isEqualTo(200);
        assertThat(get(grafana + "/api/health").statusCode()).isEqualTo(200);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            Path script = dir.resolve("telemetry.js");
            Files.writeString(script, """
                    import http from 'k6/http';
                    import { check, sleep } from 'k6';
                    export const options = { vus: 1, iterations: 6, thresholds: {
                      checks: ['rate==1'], http_req_failed: ['rate==0'], dropped_iterations: ['count==0']
                    }};
                    export default function () {
                      check(http.get(__ENV.TESTFORGE_LOAD_BASE_URL + '/health', { tags: {name: 'GET /health'} }),
                        { healthy: r => r.status === 200 });
                      sleep(1.1);
                    }
                    export function handleSummary(data) {
                      return { [__ENV.TESTFORGE_LOAD_SUMMARY]: JSON.stringify(data) };
                    }
                    """);
            PrometheusProperties output = new PrometheusProperties(true, prometheus + "/api/v1/write", null,
                    "testforge", "acceptance", grafana);
            LoadProperties properties = new LoadProperties(true, System.getProperty("testforge.k6"), null,
                    script.toString(), "http://127.0.0.1:" + server.getAddress().getPort(),
                    System.getProperty("testforge.observability-output"), Duration.ofSeconds(30), Map.of(), true, output);
            LoadReport report = new LoadRunner(properties, new LocalLoadProcess(), null).assertHealthy();
            String selector = "{run_id=\"" + report.runId() + "\"}";
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                JsonNode samples = query(prometheus, "sum(max_over_time(k6_http_reqs_total" + selector + "[5m]))", null);
                assertThat(samples.size()).isEqualTo(1);
                assertThat(samples.get(0).path("value").get(1).asDouble()).isEqualTo(6);
            });
            String counter = "sum(max_over_time(k6_http_reqs_total" + selector + "[5m]))";
            HttpResponse<String> proxied = get(grafana + "/api/datasources/proxy/uid/testforge-prometheus/api/v1/query?query="
                    + URLEncoder.encode(counter, StandardCharsets.UTF_8));
            assertThat(proxied.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(proxied.body()).path("data").path("result").get(0)
                    .path("value").get(1).asDouble()).as("Grafana datasource reaches Prometheus").isEqualTo(6);
            JsonNode dashboard = mapper.readTree(get(grafana + "/api/dashboards/uid/testforge-load").body())
                    .path("dashboard");
            assertThat(dashboard.path("uid").asText()).isEqualTo("testforge-load");
            assertThat(dashboard.path("panels").size()).isEqualTo(8);
            assertThat(dashboard.findValuesAsText("expr")).anyMatch(expr -> expr.contains("k6_vus_max"));
            String time = String.valueOf(Instant.parse(report.finishedAt()).toEpochMilli() / 1000.0 - 1.5);
            for (JsonNode panel : dashboard.path("panels")) {
                for (JsonNode target : panel.path("targets")) {
                    String expr = target.path("expr").asText()
                        .replace("$__rate_interval", "5s").replace("$__range", "5m")
                        .replace("$project", "testforge").replace("$environment", "acceptance")
                        .replace("$run_id", report.runId());
                    assertThat(query(prometheus, expr, time).size()).as(panel.path("title").asText()).isPositive();
                }
            }
            assertThat(report.dashboardUrl()).contains("var-run_id=" + report.runId());
            mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(report.directory())
                    .resolve("telemetry-check.json").toFile(), Map.of("runId", report.runId(),
                            "requestsReceived", 6, "dashboardPanelsVerified", 8, "nativeHistograms", true));
        } finally {
            server.stop(0);
        }
    }

    private HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode query(String prometheus, String expr, String time) throws Exception {
        HttpResponse<String> response = get(prometheus + "/api/v1/query?query="
                + URLEncoder.encode(expr, StandardCharsets.UTF_8) + (time == null ? "" : "&time=" + time));
        assertThat(response.statusCode()).as(expr).isEqualTo(200);
        JsonNode result = mapper.readTree(response.body());
        assertThat(result.path("status").asText()).as(expr).isEqualTo("success");
        return result.path("data").path("result");
    }
}
