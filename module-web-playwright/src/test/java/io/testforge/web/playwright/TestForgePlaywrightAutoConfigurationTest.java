package io.testforge.web.playwright;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Offline: creating the provider launches a browser, so these tests never enable it. */
class TestForgePlaywrightAutoConfigurationTest {

    @Test
    void noBrowserProviderUnlessExplicitlyEnabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TestForgePlaywrightAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(PlaywrightProvider.class));
    }

    @Test
    void enabledFalseStaysInert() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TestForgePlaywrightAutoConfiguration.class))
                .withPropertyValues("forge.playwright.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(PlaywrightProvider.class));
    }

    @Test
    void propertiesDefaultToHeadlessChromiumWithFailureArtifacts() {
        PlaywrightProperties properties = new PlaywrightProperties(null, null, null, null, " ");

        assertThat(properties.headless()).isTrue();
        assertThat(properties.browserType()).isEqualTo("chromium");
        assertThat(properties.defaultTimeout()).isEqualTo(30_000.0);
        assertThat(properties.artifactsOnFailure()).isTrue();
        assertThat(properties.artifactsDir()).isEqualTo("build/playwright-artifacts");
    }
}
