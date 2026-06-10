package io.testforge.flow;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(FlowProperties.class)
public class TestForgeFlowAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FlowRunnerFactory flowRunnerFactory(FlowProperties properties) {
        return new FlowRunnerFactory(properties);
    }
}
