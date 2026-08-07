package io.testforge.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.filter.Filter;
import io.testforge.core.wait.WaitProperties;
import io.testforge.core.wait.Waiter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@AutoConfiguration
@EnableConfigurationProperties(HttpProperties.class)
public class TestForgeHttpAutoConfiguration {

    /**
     * Module-owned mapper: request bodies are rewritten and logs are redacted
     * with fixed settings, independent of whatever the tested application
     * configures for its own {@code ObjectMapper}.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    @Bean
    @ConditionalOnMissingBean
    public Redactor httpRedactor(HttpProperties properties) {
        return new Redactor(mapper, properties.logging().redactHeaders(), properties.logging().redactJsonFields());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "forge.http.correlation", name = "enabled", matchIfMissing = true)
    public CorrelationIdFilter correlationIdFilter(HttpProperties properties) {
        return new CorrelationIdFilter(properties.correlation().header());
    }

    /**
     * The scope path defaults to {@code forge.mock.scope-json-path} when
     * {@code module-mock} is configured: the two values must describe the same
     * field, and a project that has to keep them in sync by hand eventually
     * will not. Read as a plain property so this module keeps no dependency on
     * module-mock and both stay deletable.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "forge.http.scope", name = "enabled", matchIfMissing = true)
    public ScenarioScopeFilter scenarioScopeFilter(HttpProperties properties, Environment environment) {
        String jsonPath = properties.scope().jsonPath();
        if (jsonPath == null || jsonPath.isBlank()) {
            jsonPath = environment.getProperty("forge.mock.scope-json-path", "$.testScope");
        }
        return new ScenarioScopeFilter(new JsonScopeWriter(mapper), jsonPath, properties.scope().header());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "forge.http.logging", name = "enabled", matchIfMissing = true)
    public HttpLoggingFilter httpLoggingFilter(HttpProperties properties, Redactor redactor) {
        HttpProperties.LoggingProperties logging = properties.logging();
        return new HttpLoggingFilter(redactor, logging.bodies(), logging.maxBodyChars());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "forge.http.retry", name = "enabled", havingValue = "true")
    public RetryFilter httpRetryFilter(HttpProperties properties) {
        HttpProperties.RetryProperties retry = properties.retry();
        return new RetryFilter(new Waiter(new WaitProperties(retry.timeout(), retry.delay())), retry);
    }

    /**
     * Every REST Assured {@code Filter} bean in the context is applied to
     * every request — that is the extension point for project filters
     * (authentication, signing, tracing headers). Use {@code OrderedFilter} to
     * control where a filter lands in the chain.
     */
    @Bean
    @ConditionalOnMissingBean
    public ApiClient apiClient(HttpProperties properties,
                               ObjectProvider<Filter> filters,
                               ObjectProvider<ApiRequestCustomizer> customizers) {
        return new ApiClient(properties, filters.stream().toList(), customizers.orderedStream().toList());
    }
}
