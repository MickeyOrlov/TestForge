package io.testforge.api.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.artifact.ArtifactSink;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

// after Spring Boot's Jackson: the mapper below is a fallback for a context
// without it, never a replacement for the application's own mapper, which a
// @SpringBootTest's service under test serves its requests with
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration")
@EnableConfigurationProperties(ApiDiscoveryProperties.class)
public class TestForgeApiDiscoveryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public OpenApiSpecParser openApiSpecParser() {
        return new OpenApiSpecParser();
    }

    @Bean
    @ConditionalOnMissingBean
    public EndpointCatalogBuilder endpointCatalogBuilder() {
        return new EndpointCatalogBuilder();
    }

    @Bean
    @ConditionalOnMissingBean
    public OpenApiShapeNormalizer openApiShapeNormalizer() {
        return new OpenApiShapeNormalizer();
    }

    @Bean
    @ConditionalOnMissingBean
    public ApiDiscoveryRunner apiDiscoveryRunner(
            OpenApiSpecParser parser,
            EndpointCatalogBuilder catalogBuilder,
            OpenApiShapeNormalizer normalizer,
            ObjectMapper objectMapper,
            ApiDiscoveryProperties properties,
            ObjectProvider<ArtifactSink> artifactSink) {
        return new ApiDiscoveryRunner(parser, catalogBuilder, normalizer, objectMapper, properties,
                artifactSink.getIfAvailable(() -> ArtifactSink.NO_OP));
    }
}
