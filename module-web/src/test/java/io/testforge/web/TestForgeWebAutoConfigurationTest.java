package io.testforge.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.web.prewarm.PrewarmProperties;
import io.testforge.web.prewarm.PrewarmRunner;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Offline checks: nothing here may start a browser. */
class TestForgeWebAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TestForgeWebAutoConfiguration.class));

    @Test
    void inertByDefault() {
        runner.run(context -> assertThat(context).doesNotHaveBean(PrewarmRunner.class));
    }

    @Test
    void enabledWithoutUrlsStartsWithoutLaunchingABrowser() {
        runner.withPropertyValues("forge.prewarm.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PrewarmRunner.class);
                });
    }

    @Test
    void propertiesDefaults() {
        PrewarmProperties properties = new PrewarmProperties(false, null, null, null);

        assertThat(properties.urls()).isEmpty();
        assertThat(properties.headless()).isTrue();
        assertThat(properties.navigationTimeout()).isEqualTo(Duration.ofSeconds(40));
    }
}
