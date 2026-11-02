package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.artifact.ArtifactSink;
import io.testforge.load.LoadProcess;
import io.testforge.load.LoadReport;
import io.testforge.load.LoadRunner;
import io.testforge.load.TestForgeLoadAutoConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Offline wiring example: the process seam stands in for k6; real acceptance is module-load:k6Test. */
class LoadExampleTest {
    @TempDir Path dir;

    @Test void runsANativeScenarioAndPublishesItsVerdict() throws Exception {
        Path script = dir.resolve("orders.js");
        Files.writeString(script, "// In a real job use docs/examples/load-api.js\n");
        LoadProcess k6 = (command, cwd, env, timeout) -> {
            if (command.contains("version")) {
                return new LoadProcess.Result(0, false, "k6 v2.3.0 (example)");
            }
            Files.writeString(Path.of(env.get("TESTFORGE_LOAD_SUMMARY")), """
                    {"metrics": {
                      "iterations": {"values": {"count": 2}},
                      "http_reqs": {"values": {"count": 2, "rate": 1}},
                      "http_req_failed": {"values": {"rate": 0}, "thresholds": {"rate<0.01": {"ok": true}}}
                    }}
                    """);
            return new LoadProcess.Result(0, false, "");
        };
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(TestForgeLoadAutoConfiguration.class))
                .withBean(LoadProcess.class, () -> k6)
                .withBean(ArtifactSink.class, () -> ArtifactSink.NO_OP)
                .withPropertyValues("forge.load.enabled=true", "forge.load.script=" + script,
                        "forge.load.base-url=http://127.0.0.1:8080", "forge.load.output-dir=" + dir.resolve("runs"))
                .run(context -> {
                    LoadReport report = context.getBean(LoadRunner.class).assertHealthy();
                    assertThat(report.outcome()).isEqualTo(LoadReport.Outcome.PASSED);
                    assertThat(report.metrics().requests()).isEqualTo(2);
                });
    }
}
