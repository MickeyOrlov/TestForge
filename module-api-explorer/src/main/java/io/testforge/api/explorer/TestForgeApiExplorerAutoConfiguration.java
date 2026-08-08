package io.testforge.api.explorer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.api.discovery.ApiDiscoveryProperties;
import io.testforge.api.discovery.OpenApiSpecParser;
import io.testforge.http.ApiClient;
import io.testforge.http.Redactor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * The whole Spring surface of this module: wiring, and a switch.
 *
 * <p>Nothing here holds exploration logic. Every bean below is a plain Java
 * object with a constructor, which is what lets the interesting behaviour be
 * tested without a context — and what lets a project assemble the pipeline by
 * hand if it wants a different executor.
 *
 * <p>No bean exists at all unless {@code forge.api-explorer.enabled=true}. A
 * module that sends live traffic should not be one property away from doing so
 * because it happens to be on the classpath.
 */
@AutoConfiguration(afterName = {
        "io.testforge.api.discovery.TestForgeApiDiscoveryAutoConfiguration",
        "io.testforge.http.TestForgeHttpAutoConfiguration"})
@EnableConfigurationProperties({ApiExplorerProperties.class, ApiDiscoveryProperties.class})
@ConditionalOnProperty(prefix = "forge.api-explorer", name = "enabled", havingValue = "true")
public class TestForgeApiExplorerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OperationSelector operationSelector() {
        return new OperationSelector();
    }

    @Bean
    @ConditionalOnMissingBean
    public SafetyPolicy apiExplorerSafetyPolicy(ApiExplorerProperties properties) {
        return SafetyPolicy.from(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public SchemaValueFactory schemaValueFactory() {
        return new SchemaValueFactory();
    }

    @Bean
    @ConditionalOnMissingBean
    public RequestValueResolver requestValueResolver(ApiExplorerProperties properties, SchemaValueFactory values) {
        return new RequestValueResolver(properties.parameters(), values);
    }

    @Bean
    @ConditionalOnMissingBean
    public RequestPlanner requestPlanner(RequestValueResolver values) {
        return new RequestPlanner(values);
    }

    /** Swappable on purpose: a replay stage would provide its own. */
    @Bean
    @ConditionalOnMissingBean
    public ExchangeExecutor exchangeExecutor(ApiClient apiClient, ApiExplorerProperties properties) {
        return new ApiClientExchangeExecutor(apiClient, properties.service());
    }

    @Bean
    @ConditionalOnMissingBean
    public ResponseContractChecker responseContractChecker(ObjectMapper objectMapper) {
        return new ResponseContractChecker(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public ObservationFactory observationFactory(Redactor redactor, ApiExplorerProperties properties) {
        return new ObservationFactory(redactor, properties.maxBodyChars());
    }

    @Bean
    @ConditionalOnMissingBean
    public ApiExplorerRunner apiExplorerRunner(
            OpenApiSpecParser parser,
            OperationSelector selector,
            SafetyPolicy safety,
            RequestPlanner planner,
            ExchangeExecutor executor,
            ResponseContractChecker checker,
            ObservationFactory observations,
            ObjectMapper objectMapper,
            ApiDiscoveryProperties discoveryProperties,
            ApiExplorerProperties properties) {

        return new ApiExplorerRunner(parser, selector, safety, planner, executor, checker, observations,
                objectMapper, discoveryProperties, properties);
    }
}
