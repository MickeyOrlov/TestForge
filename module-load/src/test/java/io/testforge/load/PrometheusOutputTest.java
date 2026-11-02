package io.testforge.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrometheusOutputTest {
    @TempDir Path dir;

    @Test void nativeOutputIsOptInLabelsArePerRunAndSecretsStayOutOfEvidence() throws Exception {
        Path script = dir.resolve("test.js");
        Files.writeString(script, "// trusted fixture");
        PrometheusProperties prometheus = new PrometheusProperties(true, "https://metrics.example.test/api/v1/write",
                "bearer-secret", "orders", "staging", "https://grafana.example.test/grafana/", true);
        LoadProperties configured = new LoadProperties(true, "k6", null, script.toString(), "https://api.example.test",
                dir.resolve("runs").toString(), Duration.ofSeconds(5), Map.of(), false, prometheus);
        LoadProcess process = (command, cwd, env, timeout) -> {
            if (command.contains("version")) return new LoadProcess.Result(0, false, "k6 v2.3.0 (fixture)");
            assertThat(command).containsSequence("--out", "experimental-prometheus-rw");
            assertThat(command).containsSequence("--system-tags", LoadRunner.SYSTEM_TAGS);
            assertThat(List.of(LoadRunner.SYSTEM_TAGS.split(","))).as("bounded tags thresholds filter on stay")
                    .contains("group", "check", "scenario", "status").doesNotContain("url", "name", "error");
            assertThat(command.toString()).doesNotContain("bearer-secret", "metrics.example.test");
            assertThat(env).containsEntry("K6_PROMETHEUS_RW_BEARER_TOKEN", "bearer-secret")
                    .containsEntry("K6_PROMETHEUS_RW_TREND_AS_NATIVE_HISTOGRAM", "true")
                    .containsEntry("K6_PROMETHEUS_RW_STALE_MARKERS", "true");
            assertThat(env.get("K6_PROMETHEUS_RW_LABELS"))
                    .isEqualTo("project=orders,environment=staging,run_id=" + env.get("TESTFORGE_LOAD_RUN_ID")
                            + ",generator=local");
            Files.writeString(Path.of(env.get("TESTFORGE_LOAD_SUMMARY")), LoadRunnerTest.SUMMARY);
            return new LoadProcess.Result(0, false, "");
        };
        LoadRunner runner = new LoadRunner(configured, process, null);
        LoadReport first = runner.assertHealthy();
        LoadReport second = runner.assertHealthy();
        assertThat(first.dashboardUrl()).startsWith("https://grafana.example.test/grafana/d/testforge-load/")
                .contains("var-project=orders", "var-environment=staging", "var-run_id=" + first.runId());
        assertThat(first.dashboardUrl()).isNotEqualTo(second.dashboardUrl());
        assertThat(Files.readString(Path.of(first.directory()).resolve("report.json")))
                .doesNotContain("bearer-secret", "metrics.example.test");
        assertThat(prometheus.toString()).doesNotContain("bearer-secret");
        assertThat(new LoadProperties(false, null, null, null, null, null, null, null, false)
                .prometheus().enabled()).isFalse();
    }

    @Test void defaultMetricsReuseSeriesAcrossRunsAndDashboardSelectsAllRuns() throws Exception {
        Path script = dir.resolve("plain.js");
        Files.writeString(script, "// fixture");
        PrometheusProperties output = new PrometheusProperties(true, null, null, "orders", "test", null);
        LoadProcess process = (command, cwd, env, timeout) -> {
            if (command.contains("version")) return new LoadProcess.Result(0, false, "k6 v2.3.0 (fixture)");
            assertThat(env.get("K6_PROMETHEUS_RW_LABELS"))
                    .isEqualTo("project=orders,environment=test,generator=local");
            Files.writeString(Path.of(env.get("TESTFORGE_LOAD_SUMMARY")), LoadRunnerTest.SUMMARY);
            return new LoadProcess.Result(0, false, "");
        };
        LoadRunner runner = new LoadRunner(new LoadProperties(true, "k6", null, script.toString(),
                "http://127.0.0.1", dir.toString(), Duration.ofSeconds(5), Map.of(), false, output), process, null);
        LoadReport first = runner.assertHealthy();
        LoadReport second = runner.assertHealthy();
        assertThat(first.dashboardUrl()).contains("var-run_id=%24__all").doesNotContain(first.runId());
        assertThat(second.dashboardUrl()).contains("var-run_id=%24__all").doesNotContain(second.runId());
    }

    @Test void endpointsAndLabelsRejectCredentialsAndInjection() {
        for (String url : new String[] {"https://user:secret@host/api/v1/write", "http://host/?token=secret", "file:///x"}) {
            assertThatThrownBy(() -> new PrometheusProperties(true, url, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("secret");
        }
        assertThatThrownBy(() -> new PrometheusProperties(true, null, null, "project,run_id=evil", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
