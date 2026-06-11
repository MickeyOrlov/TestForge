package io.testforge.web.playwright;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(PlaywrightProperties.class)
@ConditionalOnProperty(prefix = "testforge.web.playwright", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TestForgePlaywrightAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PlaywrightProvider playwrightProvider(PlaywrightProperties properties) {
        return new PlaywrightProvider(properties);
    }
}
