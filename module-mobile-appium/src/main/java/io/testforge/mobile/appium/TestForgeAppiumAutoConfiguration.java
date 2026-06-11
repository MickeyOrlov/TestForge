package io.testforge.mobile.appium;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(AppiumProperties.class)
@ConditionalOnProperty(prefix = "forge.mobile.appium", name = "enabled", havingValue = "true")
public class TestForgeAppiumAutoConfiguration {

    @Bean
    public AppiumDriverFactory appiumDriverFactory(AppiumProperties properties) {
        return new AppiumDriverFactory(properties);
    }
}
