package io.testforge.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LoadConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TestForgeLoadAutoConfiguration.class));

    @Test void inertByDefaultAndEnablingOnlyCreatesBeans() {
        context.run(c -> assertThat(c).doesNotHaveBean(LoadRunner.class).doesNotHaveBean(LoadProcess.class));
        context.withPropertyValues("forge.load.enabled=true", "forge.load.environment.API_TOKEN=private")
                .run(c -> {
                    assertThat(c).hasSingleBean(LoadRunner.class).hasSingleBean(LoadProcess.class);
                    assertThat(c.getBean(LoadProperties.class).environment()).containsEntry("API_TOKEN", "private");
                });
        context.withPropertyValues("forge.load.enabled=true", "forge.load.prometheus.enabled=true",
                        "forge.load.prometheus.project=orders", "forge.load.prometheus.bearer-token=private",
                        "forge.load.prometheus.run-ids=true",
                        "forge.load.policy.require-http=false")
                .run(c -> {
                    assertThat(c).hasNotFailed();
                    assertThat(c.getBean(LoadProperties.class).prometheus().project()).isEqualTo("orders");
                    assertThat(c.getBean(LoadProperties.class).prometheus().bearerToken()).isEqualTo("private");
                    assertThat(c.getBean(LoadProperties.class).prometheus().runIds()).isTrue();
                    assertThat(c.getBean(LoadProperties.class).policy().requireHttp()).isFalse();
                });
    }

    @Test void rejectsAmbientControlOverridesAndInvalidTargets() {
        for (String key : new String[] {"K6_OUT", "TESTFORGE_LOAD_SUMMARY", "PATH", "BAD=KEY",
                "k6_out", "Path", "testforge_load_summary"}) {
            assertThatThrownBy(() -> new LoadProperties(true, null, null, "s.js", "http://127.0.0.1", null,
                    null, Map.of(key, "secret"), false)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new LoadProperties(true, null, null, "s.js", "http://127.0.0.1", null,
                Duration.ZERO, null, false)).isInstanceOf(IllegalArgumentException.class);
        for (String target : new String[] {"", "file:///tmp/x", "https://user:secret@host", "https://host?token=x"}) {
            LoadProperties p = new LoadProperties(true, null, null, "s.js", target, null, null, null, false);
            assertThatThrownBy(p::validateRun).isInstanceOf(LoadException.class).hasMessageNotContaining("secret");
        }
    }
}
