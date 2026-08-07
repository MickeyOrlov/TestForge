package io.testforge.api.discovery;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Nothing here is created unless {@code forge.api-discovery.enabled=true}.
 *
 * <p>That is the outermost of the module's safety layers: a project that has
 * this module on the classpath but has not switched it on in an environment
 * profile cannot reach a real service, because the beans that would do it do
 * not exist.
 */
@AutoConfiguration(afterName = {
        "io.testforge.http.TestForgeHttpAutoConfiguration",
        "io.testforge.contract.TestForgeContractAutoConfiguration"})
@EnableConfigurationProperties(ApiDiscoveryProperties.class)
@ConditionalOnProperty(prefix = "forge.api-discovery", name = "enabled", havingValue = "true")
public class TestForgeApiDiscoveryAutoConfiguration {
}
