package io.testforge.flow;

import io.testforge.artifact.ArtifactSink;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(FlowProperties.class)
public class TestForgeFlowAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FlowRunnerFactory flowRunnerFactory(
            FlowProperties properties,
            ObjectProvider<ArtifactSink> artifactSinkProvider) {
        ArtifactSink artifactSink = artifactSinkProvider.getIfAvailable(() -> ArtifactSink.NO_OP);
        return new FlowRunnerFactory(properties, artifactSink);
    }
}

