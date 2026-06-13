package io.testforge.state;

import io.testforge.flow.FlowRunnerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(afterName = "io.testforge.flow.TestForgeFlowAutoConfiguration")
@EnableConfigurationProperties(StateProperties.class)
public class TestForgeStateAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public StateRecipeExecutor stateRecipeExecutor(
            FlowRunnerFactory flowRunnerFactory,
            StateProperties properties) {
        return new StateRecipeExecutor(flowRunnerFactory, properties);
    }
}
