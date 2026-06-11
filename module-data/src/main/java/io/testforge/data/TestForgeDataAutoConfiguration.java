package io.testforge.data;

import io.testforge.data.prepared.PoolEventListener;
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
    public RunUniqueValues runUniqueValues() {
        return new RunUniqueValues();
    }

    @Bean
    @ConditionalOnMissingBean
    public TemplateRenderer templateRenderer(DataProperties properties) {
        return new TemplateRenderer(properties);
    }
}
