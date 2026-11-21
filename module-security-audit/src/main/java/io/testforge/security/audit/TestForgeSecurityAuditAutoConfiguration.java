package io.testforge.security.audit;

import io.testforge.artifact.ArtifactSink;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties({
    SecurityAuditProperties.class,
    SecurityAuditResultsProperties.class,
    SecurityAuditExecutionProperties.class
})
public class TestForgeSecurityAuditAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public SecurityAuditExecutor securityAuditExecutor(
            SecurityAuditProperties properties,
            SecurityAuditExecutionProperties execution,
            SecurityAuditResultsProperties results,
            ObjectProvider<SecurityAuditAdapter> adapters,
            ObjectProvider<SecurityAuditExecutionContext.Secrets> secrets,
            ObjectProvider<ArtifactSink> sink) {
        return new SecurityAuditExecutor(
                properties,
                execution,
                results,
                adapters.orderedStream().toList(),
                secrets.getIfAvailable(
                        () ->
                                reference -> {
                                    throw new IllegalStateException(
                                            "audit credential resolver is not configured");
                                }),
                sink.getIfAvailable(() -> ArtifactSink.NO_OP));
    }

    @Bean
    @ConditionalOnMissingBean
    public SecurityAuditImports securityAuditImports(SecurityAuditResultsProperties properties) {
        return new SecurityAuditImports(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public SecurityAuditRunner securityAuditRunner(
            SecurityAuditProperties properties,
            SecurityAuditResultsProperties results,
            ObjectProvider<ArtifactSink> sink) {
        return new SecurityAuditRunner(
                properties, results, sink.getIfAvailable(() -> ArtifactSink.NO_OP));
    }

    @Bean
    @ConditionalOnMissingBean
    public SecurityAuditPlanner securityAuditPlanner(SecurityAuditProperties properties) {
        return new SecurityAuditPlanner(properties);
    }
}
