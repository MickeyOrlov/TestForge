package io.testforge.mobile.appium;

import java.nio.file.Path;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(AppiumProperties.class)
@ConditionalOnProperty(prefix = "forge.mobile.appium", name = "enabled", havingValue = "true")
public class TestForgeAppiumAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AppiumDeviceRegistry appiumDeviceRegistry(AppiumProperties properties) {
        return new AppiumDeviceRegistry(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public AppiumCapabilitiesMapper appiumCapabilitiesMapper() {
        return new AppiumCapabilitiesMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public AppiumArtifactCollector appiumArtifactCollector(AppiumProperties properties) {
        return new AppiumArtifactCollector(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public AppiumDriverFactory appiumDriverFactory(
            AppiumDeviceRegistry devices,
            AppiumCapabilitiesMapper capabilitiesMapper,
            AppiumArtifactCollector artifactCollector,
            AppiumProperties properties) {
        return new AppiumDriverFactory(
                devices,
                capabilitiesMapper,
                artifactCollector,
                Path.of(properties.artifactsDir()));
    }

    @Bean
    @ConditionalOnMissingBean
    public ProcessLauncher processLauncher() {
        return new DefaultProcessLauncher();
    }

    @Bean
    @ConditionalOnMissingBean
    public AppiumStatusProbe appiumStatusProbe() {
        return new HttpAppiumStatusProbe();
    }

    @Bean
    @ConditionalOnMissingBean
    public AppiumNodeManager appiumNodeManager(
            AppiumProperties properties,
            ProcessLauncher launcher,
            AppiumStatusProbe statusProbe) {
        return new AppiumNodeManager(properties, launcher, statusProbe);
    }
}
