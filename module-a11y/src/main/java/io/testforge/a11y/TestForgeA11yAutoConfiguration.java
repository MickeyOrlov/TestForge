package io.testforge.a11y;

import io.testforge.artifact.ArtifactSink;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(A11yProperties.class)
@ConditionalOnProperty(prefix="forge.a11y", name="enabled", havingValue="true")
public class TestForgeA11yAutoConfiguration {
    @Bean @ConditionalOnMissingBean public A11yEngine a11yEngine() { return new AxeEngine(); }
    @Bean @ConditionalOnMissingBean public A11y a11y(A11yProperties properties, A11yEngine engine, ObjectProvider<ArtifactSink> sink) {
        return new A11y(properties, engine, sink.getIfAvailable(() -> ArtifactSink.NO_OP));
    }
}
