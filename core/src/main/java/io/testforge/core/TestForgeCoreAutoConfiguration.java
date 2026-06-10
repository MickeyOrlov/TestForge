package io.testforge.core;

import io.testforge.core.wait.WaitProperties;
import io.testforge.core.wait.Waiter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(WaitProperties.class)
public class TestForgeCoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Waiter waiter(WaitProperties properties) {
        return new Waiter(properties);
    }
}
