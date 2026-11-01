package io.testforge.gherkin;

import io.testforge.core.TestForgeCoreAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * What the step classes inject. The steps themselves are Cucumber glue, not
 * beans of this configuration: a project lists their packages in
 * {@code cucumber.glue} ({@code io.testforge.gherkin.glue} always, then one
 * per module it uses, e.g. {@code io.testforge.gherkin.http}).
 */
@AutoConfiguration(after = TestForgeCoreAutoConfiguration.class)
@EnableConfigurationProperties(GherkinProperties.class)
public class TestForgeGherkinAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ScenarioVariables scenarioVariables() {
        return new ScenarioVariables();
    }
}
