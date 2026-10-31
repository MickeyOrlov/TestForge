package io.testforge.api.explorer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.api.discovery.ApiDiscoveryProperties;
import io.testforge.api.discovery.OpenApiSpecParser;
import io.testforge.artifact.ArtifactSink;
import io.testforge.auth.TokenProvider;
import io.testforge.http.ApiClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Inert unless {@code forge.access-matrix.enabled=true}: the matrix sends every
 * selected operation once per role, which is real traffic. Independent of
 * {@code forge.api-explorer.enabled}; it borrows the explorer's parameter
 * values and method policy, not its runner.
 */
@AutoConfiguration(afterName = {
        "io.testforge.api.discovery.TestForgeApiDiscoveryAutoConfiguration",
        "io.testforge.http.TestForgeHttpAutoConfiguration",
        "io.testforge.auth.TestForgeAuthAutoConfiguration"})
@EnableConfigurationProperties({AccessMatrixProperties.class, ApiExplorerProperties.class,
        ApiDiscoveryProperties.class})
@ConditionalOnProperty(prefix = "forge.access-matrix", name = "enabled", havingValue = "true")
public class TestForgeAccessMatrixAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AccessMatrixRunner accessMatrixRunner(
            OpenApiSpecParser parser,
            ApiDiscoveryProperties discovery,
            ApiExplorerProperties explorer,
            AccessMatrixProperties properties,
            ApiClient apiClient,
            ObjectProvider<TokenProvider> tokens,
            ObjectMapper objectMapper,
            ObjectProvider<ArtifactSink> artifactSink) {
        TokenProvider provider = tokens.getIfAvailable();
        return new AccessMatrixRunner(parser, discovery, explorer, properties,
                new ApiClientExchangeExecutor(apiClient, explorer.service()),
                provider == null ? null : provider.roles(), objectMapper,
                artifactSink.getIfAvailable(() -> ArtifactSink.NO_OP));
    }
}
