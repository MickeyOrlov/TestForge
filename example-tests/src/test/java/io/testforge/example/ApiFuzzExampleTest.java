package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.api.discovery.TestForgeApiDiscoveryAutoConfiguration;
import io.testforge.api.fuzz.ApiFuzzOutcome;
import io.testforge.api.fuzz.ApiFuzzReport;
import io.testforge.api.fuzz.ApiFuzzRunner;
import io.testforge.api.fuzz.ProcessResult;
import io.testforge.api.fuzz.ProcessRunner;
import io.testforge.api.fuzz.TestForgeApiFuzzAutoConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@SpringBootTest(properties = {
        "forge.api-fuzz.enabled=true",
        "forge.api-fuzz.output-dir=build/api-fuzz/example",
        "forge.api-fuzz.methods=GET,POST",
        "forge.api-fuzz.base-url=http://localhost:8080",
        "forge.api-discovery.specs.demo.location=classpath:/openapi/demo-api.yaml"
})
class ApiFuzzExampleTest {

    @TestConfiguration
    static class FuzzStubConfig {
        @Bean
        @Primary
        ProcessRunner stubProcessRunner() {
            return new StubProcessRunner();
        }
    }

    static class StubProcessRunner implements ProcessRunner {
        final List<List<String>> executedCommands = new ArrayList<>();

        @Override
        public ProcessResult run(List<String> command, Path workingDir, Map<String, String> extraEnv, Duration timeout) {
            executedCommands.add(new ArrayList<>(command));
            if (command.contains("--version")) {
                return new ProcessResult(0, "st, version 4.24.3", "", false);
            }
            // It's a run command
            try {
                if (workingDir != null) {
                    Files.createDirectories(workingDir);
                    Files.writeString(workingDir.resolve("report.ndjson"), "{\"Initialize\": {\"schemathesis_version\": \"4.24.3\"}}\n");
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return new ProcessResult(0, "Success", "", false);
        }
    }

    @Autowired
    ApiFuzzRunner runner;

    @Autowired
    ProcessRunner processRunner;

    @Test
    void isTotallyInertWhenDisabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TestForgeApiDiscoveryAutoConfiguration.class, TestForgeApiFuzzAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ApiFuzzRunner.class);
                });
    }

    @Test
    void drivesPipelineEndToEndOffline() {
        ApiFuzzReport report = runner.run();
        
        assertThat(report.outcome()).isEqualTo(ApiFuzzOutcome.PASSED);
        assertThat(report.artifacts()).containsKey("demo/report.ndjson");
    }

    @Test
    void appliesSafeMethodDefaults() {
        runner.run();
        
        StubProcessRunner stub = (StubProcessRunner) processRunner;
        List<String> runCommand = stub.executedCommands.stream()
                .filter(cmd -> cmd.contains("run"))
                .findFirst()
                .orElseThrow();

        assertThat(runCommand)
                .contains("--include-method", "GET")
                .doesNotContain("POST");
    }
}
