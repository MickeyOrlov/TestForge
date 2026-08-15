package io.testforge.api.fuzz;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.api.discovery.ApiDiscoveryProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The whole Spring surface of this module: wiring, and a switch.
 *
 * <p>Nothing here holds fuzz logic. Every bean below is a plain Java
 * object with a constructor, which is what lets the interesting behaviour be
 * tested without a context — and what lets a project assemble the pipeline by
 * hand if it wants a different executor.
 *
 * <p>No bean exists at all unless {@code forge.api-fuzz.enabled=true}. A
 * module that launches an external process which sends live HTTP traffic must
 * not be one property away from doing so just because it happens to be on the classpath.
 */
@AutoConfiguration(afterName = "io.testforge.api.discovery.TestForgeApiDiscoveryAutoConfiguration")
@EnableConfigurationProperties({ApiFuzzProperties.class, ApiDiscoveryProperties.class})
@ConditionalOnProperty(prefix = "forge.api-fuzz", name = "enabled", havingValue = "true")
public class TestForgeApiFuzzAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ProcessRunner processRunner() {
        return new DefaultProcessRunner();
    }

    @Bean
    @ConditionalOnMissingBean
    public SchemathesisExecutor schemathesisExecutor(ProcessRunner processRunner, ApiFuzzProperties properties) {
        return new SchemathesisExecutor(processRunner, properties.command());
    }

    @Bean
    @ConditionalOnMissingBean
    public FuzzSafetyPolicy fuzzSafetyPolicy(ApiFuzzProperties properties) {
        return new FuzzSafetyPolicy(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public SchemathesisConfigFile schemathesisConfigFile() {
        return new SchemathesisConfigFile();
    }

    // No SchemathesisCommand bean: a command is built per spec, per run, from
    // that run's materialized spec path and generated config file. There is no
    // meaningful singleton to publish, and one built from placeholder paths
    // would only be a trap for anyone who injected it.

    @Bean
    @ConditionalOnMissingBean
    public NdjsonReportParser ndjsonReportParser() {
        return new NdjsonReportParser();
    }

    @Bean
    @ConditionalOnMissingBean
    public FuzzEvidenceWriter fuzzEvidenceWriter() {
        return new FuzzEvidenceWriter();
    }

    @Bean
    @ConditionalOnMissingBean
    public FuzzSpecMaterializer fuzzSpecMaterializer(ApiDiscoveryProperties discoveryProperties, ResourceLoader resourceLoader, ApiFuzzProperties properties) {
        return new FuzzSpecMaterializer(discoveryProperties, resourceLoader, Paths.get(properties.outputDir()));
    }

    /**
     * The target base URL comes from {@code forge.api-fuzz.base-url} and falls
     * back to {@code forge.http.base-url}. The fallback is read as a property
     * placeholder rather than by depending on {@code module-http}: this module
     * makes no JVM HTTP calls — Schemathesis owns the traffic — so pulling in a
     * REST Assured client only to read one string would cost the module its
     * independence.
     *
     * <p>The runner reads the URL from its properties, so the resolved value is
     * folded back into the record here rather than passed alongside it.
     */
    @Bean
    @ConditionalOnMissingBean
    public ApiFuzzRunner apiFuzzRunner(
            FuzzSpecMaterializer specMaterializer,
            SchemathesisExecutor executor,
            NdjsonReportParser reportParser,
            FuzzEvidenceWriter evidenceWriter,
            ApiDiscoveryProperties discoveryProperties,
            ApiFuzzProperties properties,
            @Value("${forge.api-fuzz.base-url:${forge.http.base-url:}}") String baseUrl) {
        return new ApiFuzzRunner(
                specMaterializer,
                executor,
                reportParser,
                evidenceWriter,
                discoveryProperties,
                properties.withBaseUrl(baseUrl));
    }

}
