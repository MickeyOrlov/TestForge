package io.testforge.data;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(DataProperties.class)
public class TestForgeDataAutoConfiguration {

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
