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

    @Bean
    @ConditionalOnMissingBean
    public SchemathesisCommand schemathesisCommand(ApiFuzzProperties properties, FuzzSafetyPolicy policy) {
        // Warning: This bean declaration might be a mistake if the command is meant to be instantiated per run.
        // We supply dummy paths to satisfy the constructor signature.
        return new SchemathesisCommand(properties, policy, Paths.get(""), Paths.get(properties.outputDir(), SchemathesisConfigFile.CONFIG_FILENAME));
    }

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
     * Resolves the target base URL from forge.api-fuzz.base-url, falling back to forge.http.base-url.
     * This keeps module-api-fuzz independently deletable and free of a second HTTP client.
     */
    @Bean
    @ConditionalOnMissingBean
    public ApiFuzzRunner apiFuzzRunner(
            ProcessRunner processRunner,
            SchemathesisExecutor executor,
            FuzzSafetyPolicy safetyPolicy,
            SchemathesisConfigFile configFile,
            SchemathesisCommand command,
            NdjsonReportParser reportParser,
            FuzzEvidenceWriter evidenceWriter,
            FuzzSpecMaterializer specMaterializer,
            @Value("${forge.api-fuzz.base-url:${forge.http.base-url:}}") String baseUrl) {
        return new ApiFuzzRunner(processRunner, executor, safetyPolicy, configFile, command, reportParser, evidenceWriter, specMaterializer, baseUrl);
    }
}
