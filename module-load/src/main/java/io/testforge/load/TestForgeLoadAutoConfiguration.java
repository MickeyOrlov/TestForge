package io.testforge.load;

import io.testforge.artifact.ArtifactSink;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Wiring only. Enabling the module creates beans; only an explicit runner call creates traffic. */
@AutoConfiguration
@EnableConfigurationProperties(LoadProperties.class)
@ConditionalOnProperty(prefix = "forge.load", name = "enabled", havingValue = "true")
public class TestForgeLoadAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public LoadProcess loadProcess() {
        return new LocalLoadProcess();
    }

    @Bean
    @ConditionalOnMissingBean
    public LoadRunner loadRunner(LoadProperties properties, LoadProcess process, ObjectProvider<ArtifactSink> sink) {
        return new LoadRunner(properties, process, sink.getIfAvailable(() -> ArtifactSink.NO_OP));
    }
}
