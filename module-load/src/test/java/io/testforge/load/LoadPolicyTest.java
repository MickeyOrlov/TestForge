package io.testforge.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoadPolicyTest {
    @TempDir Path dir;

    private LoadReport run(String summary, LoadPolicyProperties policy, String baseUrl) throws Exception {
        Path script = dir.resolve("scenario.ts");
        Files.writeString(script, "// native fixture");
        LoadProcess process = (command, cwd, env, timeout) -> {
            if (command.contains("version")) return new LoadProcess.Result(0, false, "k6 v2.3.0 (fixture)");
            Files.writeString(Path.of(env.get("TESTFORGE_LOAD_SUMMARY")), summary);
            return new LoadProcess.Result(0, false, "");
        };
        LoadProperties properties = new LoadProperties(true, "k6", null, script.toString(), baseUrl,
                dir.resolve("runs").toString(), Duration.ofSeconds(3), Map.of(), false, null, policy);
        return new LoadRunner(properties, process, null).run();
    }

    @Test void nonHttpAndExploratoryScenariosUseAnExplicitProjectPolicy() throws Exception {
        String noHttp = "{\"metrics\": {\"iterations\": {\"values\": {\"count\": 1}}}}";
        LoadReport report = run(noHttp, new LoadPolicyProperties(false, false, false), null);
        assertThat(report.outcome()).isEqualTo(LoadReport.Outcome.PASSED);
        assertThat(report.metrics().requests()).isZero();
        assertThat(report.metrics().thresholds()).isZero();
        assertThat(report.detail()).contains("no native thresholds configured");
        assertThat(run(noHttp, new LoadPolicyProperties(true, false, false), "http://127.0.0.1").outcome())
                .isEqualTo(LoadReport.Outcome.INVALID_RESULT);
        assertThat(run(noHttp, new LoadPolicyProperties(false, true, false), null).outcome())
                .isEqualTo(LoadReport.Outcome.INVALID_RESULT);
    }

    @Test void droppedIterationsCanBeMeasuredWithoutInvalidatingAnExploratoryRun() throws Exception {
        String dropped = LoadRunnerTest.SUMMARY.replace("\"count\": 0", "\"count\": 5");
        LoadReport report = run(dropped, new LoadPolicyProperties(true, true, false), "http://127.0.0.1");
        assertThat(report.outcome()).isEqualTo(LoadReport.Outcome.PASSED);
        assertThat(report.metrics().droppedIterations()).isEqualTo(5);
        assertThat(run(dropped, new LoadPolicyProperties(true, true, true), "http://127.0.0.1").outcome())
                .isEqualTo(LoadReport.Outcome.INVALID_RESULT);
        assertThat(run(dropped.replace("\"ok\": true", "\"ok\": false"),
                new LoadPolicyProperties(false, false, false), null).outcome())
                .as("project policy never suppresses a failed native threshold")
                .isEqualTo(LoadReport.Outcome.THRESHOLDS_FAILED);
    }
}
