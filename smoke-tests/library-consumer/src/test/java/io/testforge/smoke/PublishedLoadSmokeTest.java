package io.testforge.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.load.LoadProcess;
import io.testforge.load.LoadProperties;
import io.testforge.load.LoadRunner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Resolves module-load from its published JAR/POM, not a root-project dependency. */
class PublishedLoadSmokeTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Consumer {
    }

    @Test void publishedAutoConfigurationIsOptInAndDoesNotRunAtStartup() {
        ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(Consumer.class);
        context.run(c -> assertThat(c).doesNotHaveBean(LoadRunner.class));
        LoadProcess never = (command, cwd, environment, timeout) -> {
            throw new AssertionError("Enabling must not start a process");
        };
        context.withPropertyValues("forge.load.enabled=true", "forge.load.prometheus.enabled=true",
                        "forge.load.prometheus.project=consumer", "forge.load.policy.require-http=false")
                .withBean(LoadProcess.class, () -> never)
                .run(c -> {
                    assertThat(c).hasSingleBean(LoadRunner.class);
                    assertThat(c.getBean(LoadProcess.class)).isSameAs(never);
                    assertThat(c.getBean(LoadProperties.class).prometheus().project()).isEqualTo("consumer");
                    assertThat(c.getBean(LoadProperties.class).policy().requireHttp()).isFalse();
                });
    }
}
