package io.testforge.web;

import io.testforge.web.prewarm.PrewarmProperties;
import io.testforge.web.prewarm.PrewarmRunner;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(PrewarmProperties.class)
@ConditionalOnProperty(prefix = "forge.prewarm", name = "enabled", havingValue = "true")
public class TestForgeWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PrewarmRunner prewarmRunner(PrewarmProperties properties) {
        return new PrewarmRunner(properties);
    }

    /**
     * Triggers prewarm when the Spring context finishes starting — i.e. once
     * per suite, before any test method runs, with no JUnit/TestNG coupling.
     */
    @Bean
    public SmartInitializingSingleton prewarmOnStartup(PrewarmRunner runner) {
        return runner::runOnce;
    }
}
