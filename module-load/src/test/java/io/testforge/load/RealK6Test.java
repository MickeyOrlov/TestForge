package io.testforge.load;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Opt-in acceptance: three requests to a server this test owns, never a public/shared environment. */
@Tag("k6")
class RealK6Test {
    @TempDir Path dir;

    @Test void aNativeNonHttpScenarioCanRunWithoutProjectThresholdsWhenExplicitlyAllowed() throws Exception {
        Path script = dir.resolve("native-only.js");
        Files.writeString(script, """
                export const options = { iterations: 1 };
                export default function () {}
                export function handleSummary(data) {
                  return { [__ENV.TESTFORGE_LOAD_SUMMARY]: JSON.stringify(data) };
                }
                """);
        LoadProperties properties = new LoadProperties(true, System.getProperty("testforge.k6"), null,
                script.toString(), null, dir.resolve("native").toString(), Duration.ofSeconds(10), Map.of(), false,
                null, new LoadPolicyProperties(false, false, false));
        assertThat(new LoadRunner(properties, new LocalLoadProcess(), null).assertHealthy().metrics().iterations())
                .isEqualTo(1);
    }

    /**
     * The regression on a real k6: with remote write on, a threshold on a group or a check still sees its
     * samples, and a filter that matches nothing is reported instead of passing.
     */
    @Test void thresholdsOnGroupsAndChecksHoldWithRemoteWriteAndAnEmptyFilterIsCaught() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            // /api/v1/write stands in for Prometheus; everything else is the target
            exchange.sendResponseHeaders(exchange.getRequestURI().getPath().endsWith("/write") ? 204 : 200, -1);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Path script = dir.resolve("groups.js");
            String template = """
                    import http from 'k6/http';
                    import { check, group } from 'k6';
                    export const options = { vus: 1, iterations: 3, thresholds: {
                      'THRESHOLD': ['LIMIT'], http_req_failed: ['rate==0'] }};
                    export default function () {
                      group('owners', () => {
                        check(http.get(__ENV.TESTFORGE_LOAD_BASE_URL + '/owners'), { 'answers': r => r.status === 200 });
                      });
                    }
                    export function handleSummary(data) {
                      return { [__ENV.TESTFORGE_LOAD_SUMMARY]: JSON.stringify(data) };
                    }
                    """;
            PrometheusProperties prometheus = new PrometheusProperties(true, base + "/api/v1/write", null, "probe",
                    "local", base, false);
            LoadProperties properties = new LoadProperties(true, System.getProperty("testforge.k6"), null,
                    script.toString(), base, dir.resolve("groups").toString(), Duration.ofSeconds(30), Map.of(), false,
                    prometheus);
            LoadRunner runner = new LoadRunner(properties, new LocalLoadProcess(), null);

            Files.writeString(script, template.replace("THRESHOLD", "http_req_duration{group:::owners}")
                    .replace("LIMIT", "p(95)<0.001"));
            assertThat(runner.run().outcome()).as("an impossible group SLO fails with remote write on")
                    .isEqualTo(LoadReport.Outcome.THRESHOLDS_FAILED);

            Files.writeString(script, template.replace("THRESHOLD", "checks{check:answers}").replace("LIMIT", "rate==0"));
            assertThat(runner.run().outcome()).as("a check threshold sees its samples")
                    .isEqualTo(LoadReport.Outcome.THRESHOLDS_FAILED);

            Files.writeString(script, template.replace("THRESHOLD", "http_req_duration{group:::ownerz}")
                    .replace("LIMIT", "p(95)<0.001"));
            LoadReport typo = runner.run();
            assertThat(typo.outcome()).as("k6 passes a filter matching nothing; the runner does not")
                    .isEqualTo(LoadReport.Outcome.INVALID_RESULT);
            assertThat(typo.metrics().emptyThresholds()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test void realK6WritesNativeSummaryHtmlAndThresholdFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            Path script = dir.resolve("smoke.js");
            Files.writeString(script, """
                    import http from 'k6/http';
                    import { check, sleep } from 'k6';
                    export const options = { vus: 1, iterations: 3, thresholds: {
                      checks: ['rate==1'], http_req_failed: ['rate==0'], iterations: ['count==3']
                    }};
                    export default function () {
                      check(http.get(__ENV.TESTFORGE_LOAD_BASE_URL + '/health'), { 'healthy': r => r.status === 200 });
                      sleep(1.1); // Native user pacing; enough dashboard samples for the HTML export.
                    }
                    export function handleSummary(data) {
                      return { [__ENV.TESTFORGE_LOAD_SUMMARY]: JSON.stringify(data),
                        [__ENV.TESTFORGE_LOAD_SUMMARY + '.extra.json']: 'private extra output' };
                    }
                    """);
            LoadProperties properties = new LoadProperties(true, System.getProperty("testforge.k6"), null,
                    script.toString(), "http://127.0.0.1:" + server.getAddress().getPort(), dir.resolve("runs").toString(),
                    Duration.ofSeconds(20), Map.of(), true);
            LoadRunner runner = new LoadRunner(properties, new LocalLoadProcess(), null);
            LoadReport passed = runner.assertHealthy();
            assertThat(passed.metrics().requests()).isEqualTo(3);
            assertThat(Path.of(passed.directory()).resolve("k6-report.html")).isRegularFile();
            Files.writeString(script, Files.readString(script).replace("count==3", "count>100"));
            LoadReport failed = runner.run();
            assertThat(failed.outcome()).isEqualTo(LoadReport.Outcome.THRESHOLDS_FAILED);
            assertThat(failed.exitCode()).isEqualTo(99);
            assertThat(failed.metrics().failedThresholds()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }
}
