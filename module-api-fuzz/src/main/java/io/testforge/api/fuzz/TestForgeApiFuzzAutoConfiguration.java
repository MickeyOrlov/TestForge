package io.testforge.api.fuzz;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.api.discovery.ApiDiscoveryProperties;
import io.testforge.api.discovery.OpenApiSpecParser;
import io.testforge.api.explorer.ExchangeExecutor;
import io.testforge.api.explorer.ObservationFactory;
import io.testforge.api.explorer.OperationSelector;
import io.testforge.api.explorer.RequestPlanner;
import io.testforge.api.explorer.RequestValueResolver;
import io.testforge.api.explorer.ResponseContractChecker;
import io.testforge.api.explorer.SafetyPolicy;
import io.testforge.api.explorer.SchemaValueFactory;
import io.testforge.http.ApiClient;
import io.testforge.http.Redactor;
import io.testforge.api.explorer.ApiClientExchangeExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wiring only; the fuzzing itself is plain Java.
 *
 * <p>No bean exists unless {@code forge.api-fuzz.enabled=true}. This is the
 * module that deliberately sends bad data at a running service, so being on the
 * classpath must never be enough to make it do so.
 *
 * <p>The explorer's beans are reused when its own auto-configuration is active
 * and constructed here when it is not, so fuzzing works whether or not a
 * project also runs exploration.
 */
@AutoConfiguration(afterName = {
        "io.testforge.api.discovery.TestForgeApiDiscoveryAutoConfiguration",
        "io.testforge.api.explorer.TestForgeApiExplorerAutoConfiguration",
        "io.testforge.http.TestForgeHttpAutoConfiguration"})
@EnableConfigurationProperties({ApiFuzzProperties.class, ApiDiscoveryProperties.class})
@ConditionalOnProperty(prefix = "forge.api-fuzz", name = "enabled", havingValue = "true")
public class TestForgeApiFuzzAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FuzzCaseGenerator fuzzCaseGenerator() {
        return new FuzzCaseGenerator();
    }

    @Bean
    @ConditionalOnMissingBean
    public JsonBodyFactory jsonBodyFactory(ObjectMapper objectMapper) {
        return new JsonBodyFactory(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public BodyCaseGenerator bodyCaseGenerator(ObjectMapper objectMapper, JsonBodyFactory bodyFactory) {
        return new BodyCaseGenerator(objectMapper, bodyFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public JsonBodyMutator jsonBodyMutator(ObjectMapper objectMapper) {
        return new JsonBodyMutator(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConstraintInventory constraintInventory(JsonBodyFactory bodyFactory) {
        return new ConstraintInventory(bodyFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public BaselineSelfCheck baselineSelfCheck() {
        return new BaselineSelfCheck();
    }

    @Bean
    @ConditionalOnMissingBean
    public FuzzCaseSelector fuzzCaseSelector(ApiFuzzProperties properties) {
        return new FuzzCaseSelector(properties.seed(), properties.maxCasesPerOperation());
    }

    @Bean
    @ConditionalOnMissingBean
    public ResponseClassifier responseClassifier(ObjectProvider<ResponseContractChecker> checkers,
                                                 ObjectMapper objectMapper) {
        return new ResponseClassifier(
                checkers.getIfAvailable(() -> new ResponseContractChecker(objectMapper)), objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public ApiFuzzRunner apiFuzzRunner(
            OpenApiSpecParser parser,
            ObjectProvider<OperationSelector> selectors,
            ObjectProvider<ExchangeExecutor> executors,
            ObjectProvider<ObservationFactory> observationFactories,
            FuzzCaseGenerator generator,
            BodyCaseGenerator bodyCases,
            JsonBodyFactory bodyFactory,
            JsonBodyMutator bodyMutator,
            ConstraintInventory inventory,
            BaselineSelfCheck selfCheck,
            FuzzCaseSelector cases,
            ResponseClassifier classifier,
            ApiClient apiClient,
            Redactor redactor,
            ObjectMapper objectMapper,
            ApiDiscoveryProperties discoveryProperties,
            ApiFuzzProperties properties) {

        SafetyPolicy safety = new SafetyPolicy(properties.methods(), properties.allowUnsafeMethods(),
                properties.includePaths(), properties.excludePaths());
        // bodies are this module's job, so the planner stops refusing the
        // operations that need one and leaves the body to the caller
        RequestPlanner planner = new RequestPlanner(
                new RequestValueResolver(properties.parameters(), new ConstraintAwareValueFactory()), true);

        return new ApiFuzzRunner(
                parser,
                selectors.getIfAvailable(OperationSelector::new),
                safety,
                planner,
                generator,
                bodyCases,
                bodyFactory,
                bodyMutator,
                inventory,
                selfCheck,
                cases,
                executors.getIfAvailable(() -> new ApiClientExchangeExecutor(apiClient, properties.service())),
                classifier,
                observationFactories.getIfAvailable(
                        () -> new ObservationFactory(redactor, properties.maxBodyChars())),
                objectMapper,
                discoveryProperties,
                properties);
    }
}
