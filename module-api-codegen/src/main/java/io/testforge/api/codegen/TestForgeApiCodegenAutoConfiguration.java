package io.testforge.api.codegen;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.api.discovery.ApiDiscoveryProperties;
import io.testforge.api.discovery.OpenApiSpecParser;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(afterName = "io.testforge.api.discovery.TestForgeApiDiscoveryAutoConfiguration")
@EnableConfigurationProperties({ApiCodegenProperties.class, ApiDiscoveryProperties.class})
@ConditionalOnProperty(prefix = "forge.api-codegen", name = "enabled", havingValue = "true")
public class TestForgeApiCodegenAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OpenApiJavaCodeGenerator openApiJavaCodeGenerator() {
        return new OpenApiJavaCodeGenerator();
    }

    @Bean
    @ConditionalOnMissingBean
    public ApiCodegenRunner apiCodegenRunner(
            OpenApiSpecParser parser,
            OpenApiJavaCodeGenerator generator,
            ObjectMapper objectMapper,
            ApiDiscoveryProperties discoveryProperties,
            ApiCodegenProperties properties) {
        return new ApiCodegenRunner(parser, generator, objectMapper, discoveryProperties, properties);
    }
}
