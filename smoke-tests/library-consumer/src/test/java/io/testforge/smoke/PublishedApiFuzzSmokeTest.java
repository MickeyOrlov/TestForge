package io.testforge.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.api.fuzz.ApiFuzzOutcome;
import io.testforge.api.fuzz.ApiFuzzProperties;
import io.testforge.api.fuzz.ApiFuzzRunner;
import io.testforge.api.fuzz.ProcessResult;
import io.testforge.api.fuzz.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The fuzz module as an external consumer sees it: resolved from the published
 * artifact, auto-configured from the JAR's own metadata.
 */
class PublishedApiFuzzSmokeTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SmokeTestApplication.class, NoOpProcessRunnerConfig.class);

    @Configuration
    static class NoOpProcessRunnerConfig {
        @Bean
        public ProcessRunner noOpProcessRunner() {
            return (command, workingDirectory, env, timeout) -> new ProcessResult(0, "", "", false);
        }
    }

    @Test
    void isInertByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(ApiFuzzRunner.class);
        });
    }

    @Test
    void createsRunnerWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "forge.api-fuzz.enabled=true",
                        "forge.api-discovery.specs.demo.location=classpath:/openapi/demo-api.yaml"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ApiFuzzRunner.class);
                    assertThat(context).hasSingleBean(ApiFuzzProperties.class);

                    ApiFuzzProperties props = context.getBean(ApiFuzzProperties.class);
                    assertThat(props.methods()).containsExactlyInAnyOrder("GET", "HEAD", "OPTIONS");
                    assertThat(props.permits("POST")).isFalse();

                    // Verify ApiFuzzOutcome is accessible
                    assertThat(ApiFuzzOutcome.PASSED).isNotNull();
                });
    }
}
