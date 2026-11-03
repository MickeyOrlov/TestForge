package io.testforge.faults;

import io.testforge.artifact.ArtifactSink;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(FaultsProperties.class)
@ConditionalOnProperty(prefix="forge.faults", name="enabled", havingValue="true")
public class TestForgeFaultsAutoConfiguration {
    @Bean @ConditionalOnMissingBean
    public FaultBackend faultBackend(FaultsProperties properties) { return new ToxiproxyBackend(properties); }
    @Bean @ConditionalOnMissingBean
    public Faults faults(FaultsProperties properties, FaultBackend backend, ObjectProvider<ArtifactSink> sink) {
        return new Faults(properties, backend, sink.getIfAvailable(() -> ArtifactSink.NO_OP));
    }
}
