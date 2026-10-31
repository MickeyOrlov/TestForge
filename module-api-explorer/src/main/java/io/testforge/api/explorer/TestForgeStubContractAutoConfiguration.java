package io.testforge.api.explorer;

import io.testforge.api.discovery.ApiDiscoveryProperties;
import io.testforge.api.discovery.OpenApiSpecParser;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Inert unless {@code forge.stub-contract.enabled=true} and WireMock is on the
 * classpath ({@code module-mock} brings it). Sends no requests: it reads stub
 * definitions only.
 */
@AutoConfiguration(afterName = "io.testforge.api.discovery.TestForgeApiDiscoveryAutoConfiguration")
@ConditionalOnClass(name = "com.github.tomakehurst.wiremock.client.WireMock")
@EnableConfigurationProperties({StubContractProperties.class, ApiDiscoveryProperties.class})
@ConditionalOnProperty(prefix = "forge.stub-contract", name = "enabled", havingValue = "true")
public class TestForgeStubContractAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public StubContractChecker stubContractChecker(ApiDiscoveryProperties discovery,
                                                   StubContractProperties properties) {
        return new StubContractChecker(new OpenApiSpecParser(), discovery, properties);
    }
}
