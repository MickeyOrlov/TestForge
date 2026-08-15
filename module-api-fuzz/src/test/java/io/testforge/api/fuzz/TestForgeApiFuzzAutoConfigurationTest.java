package io.testforge.api.fuzz;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestForgeApiFuzzAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TestForgeApiFuzzAutoConfiguration.class));

    @Test
    void backsoffWhenDisabled() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(ApiFuzzRunner.class);
            assertThat(context).doesNotHaveBean(SchemathesisExecutor.class);
        });
    }

    @Test
    void wiresBeansWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "forge.api-fuzz.enabled=true",
                        "forge.api-fuzz.base-url=http://localhost:8080"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ApiFuzzRunner.class);
                    assertThat(context).hasSingleBean(ProcessRunner.class);
                    assertThat(context.getBean(ProcessRunner.class)).isInstanceOf(DefaultProcessRunner.class);
                });
    }

    @Test
    void userProcessRunnerOverridesDefault() {
        contextRunner
                .withPropertyValues(
                        "forge.api-fuzz.enabled=true",
                        "forge.api-fuzz.base-url=http://localhost:8080"
                )
                .withUserConfiguration(CustomProcessRunnerConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(ApiFuzzRunner.class);
                    assertThat(context).hasSingleBean(ProcessRunner.class);
                    assertThat(context.getBean(ProcessRunner.class)).isInstanceOf(DummyProcessRunner.class);
                });
    }

    @Configuration
    static class CustomProcessRunnerConfiguration {
        @Bean
        ProcessRunner customProcessRunner() {
            return new DummyProcessRunner();
        }
    }

    static class DummyProcessRunner implements ProcessRunner {
        @Override
        public ProcessResult run(List<String> command, Path workingDir, Map<String, String> extraEnv, Duration timeout) {
            return new ProcessResult(0, "dummy", "", false);
        }
    }
}
