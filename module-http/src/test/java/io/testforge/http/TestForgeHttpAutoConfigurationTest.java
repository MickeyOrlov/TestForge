package io.testforge.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TestForgeHttpAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TestForgeHttpAutoConfiguration.class));

    @Test
    void defaultChainHasCorrelationScopeAndLoggingButNoRetry() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ApiClient.class);
            assertThat(context).hasSingleBean(Redactor.class);
            assertThat(context).hasSingleBean(CorrelationIdFilter.class);
            assertThat(context).hasSingleBean(ScenarioScopeFilter.class);
            assertThat(context).hasSingleBean(HttpLoggingFilter.class);
            assertThat(context).doesNotHaveBean(RetryFilter.class);
        });
    }

    @Test
    void retryIsOptIn() {
        runner.withPropertyValues("forge.http.retry.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(RetryFilter.class));
    }

    @Test
    void eachFilterCanBeSwitchedOff() {
        runner.withPropertyValues(
                        "forge.http.correlation.enabled=false",
                        "forge.http.scope.enabled=false",
                        "forge.http.logging.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CorrelationIdFilter.class);
                    assertThat(context).doesNotHaveBean(ScenarioScopeFilter.class);
                    assertThat(context).doesNotHaveBean(HttpLoggingFilter.class);
                    assertThat(context).hasSingleBean(ApiClient.class);
                });
    }

    @Test
    void correlationRunsBeforeScopeAndRetryRunsInnermost() {
        runner.withPropertyValues("forge.http.retry.enabled=true").run(context -> {
            int correlation = context.getBean(CorrelationIdFilter.class).getOrder();
            int scope = context.getBean(ScenarioScopeFilter.class).getOrder();
            int retry = context.getBean(RetryFilter.class).getOrder();

            assertThat(correlation).isLessThan(scope);
            assertThat(scope).isLessThan(retry);
        });
    }
}
