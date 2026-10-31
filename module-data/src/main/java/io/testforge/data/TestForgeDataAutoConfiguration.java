package io.testforge.data;

import io.testforge.artifact.ArtifactSink;
import io.testforge.data.isolation.DataIsolation;
import io.testforge.data.prepared.PoolEventListener;
import io.testforge.data.prepared.PoolLifecycle;
import io.testforge.data.prepared.PreparedDataPool;
import io.testforge.data.prepared.PreparedDataProvider;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(DataProperties.class)
public class TestForgeDataAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PreparedDataPool preparedDataPool(
            ObjectProvider<PreparedDataProvider<?>> providers,
            ObjectProvider<PoolEventListener> listeners) {
        return new PreparedDataPool(providers.stream().toList(), listeners.stream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    public PoolLifecycle preparedPoolLifecycle(
            PreparedDataPool pool,
            DataProperties properties,
            ObjectProvider<ArtifactSink> artifactSink) {
        return new PoolLifecycle(pool, properties.pool(), artifactSink.getIfAvailable(() -> ArtifactSink.NO_OP));
    }

    /** Applies {@code forge.data.isolation} to {@link DataIsolation} when the context starts. */
    @Bean
    public DataIsolationSettings dataIsolationSettings(DataProperties properties) {
        DataIsolation.configure(properties.isolation().prefix(), properties.isolation().runTag());
        return new DataIsolationSettings(DataIsolation.runTag());
    }

    /** The run tag this context configured. */
    public record DataIsolationSettings(String runTag) {
    }

    @Bean
    @ConditionalOnMissingBean
    public RunUniqueValues runUniqueValues() {
        return new RunUniqueValues();
    }

    @Bean
    @ConditionalOnMissingBean
    public TemplateRenderer templateRenderer(DataProperties properties) {
        return new TemplateRenderer(properties);
    }
}
