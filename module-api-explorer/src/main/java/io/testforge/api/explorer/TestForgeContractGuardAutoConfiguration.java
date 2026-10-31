package io.testforge.api.explorer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.api.discovery.ApiDiscoveryProperties;
import io.testforge.api.discovery.OpenApiSpecParser;
import io.testforge.artifact.ArtifactSink;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Inert unless {@code forge.contract-guard.enabled=true}. Independent of
 * {@code forge.api-explorer.enabled}: guarding the responses of ordinary tests
 * sends no extra traffic, it only reads what the tests already received.
 */
@AutoConfiguration(afterName = "io.testforge.api.discovery.TestForgeApiDiscoveryAutoConfiguration")
@EnableConfigurationProperties({ContractGuardProperties.class, ApiDiscoveryProperties.class})
@ConditionalOnProperty(prefix = "forge.contract-guard", name = "enabled", havingValue = "true")
public class TestForgeContractGuardAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ContractGuard contractGuard(ApiDiscoveryProperties discovery, ContractGuardProperties properties) {
        return new ContractGuard(new OpenApiSpecParser(), new OperationSelector(),
                new ResponseContractChecker(new ObjectMapper()), discovery, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ContractGuardFilter contractGuardFilter(ContractGuard guard, ContractGuardProperties properties) {
        return new ContractGuardFilter(guard, properties.mode());
    }

    @Bean
    @ConditionalOnMissingBean
    public ContractGuardReporter contractGuardReporter(ContractGuard guard, ObjectProvider<ArtifactSink> sink) {
        return new ContractGuardReporter(guard, sink.getIfAvailable(() -> ArtifactSink.NO_OP));
    }
}
