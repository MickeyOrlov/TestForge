package io.testforge.security;

import io.testforge.artifact.ArtifactSink;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(
        afterName = {
            "io.testforge.http.TestForgeHttpAutoConfiguration",
            "io.testforge.auth.TestForgeAuthAutoConfiguration",
            "io.testforge.reporting.TestForgeReportingAutoConfiguration"
        })
@EnableConfigurationProperties({
    SecurityProperties.class,
    SecurityAuthProperties.class,
    SecurityTransportProperties.class,
    SecurityArtifactProperties.class,
    SecurityActiveProperties.class,
    BusinessSecurityProperties.class,
    BusinessScenarioProperties.class,
    BusinessPolicyProperties.class
})
public class TestForgeSecurityAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public BusinessPolicyRunner businessPolicyRunner(
            BusinessPolicyProperties properties,
            BusinessSecurityProperties business,
            SecurityProperties reports,
            ObjectProvider<io.testforge.auth.TokenProvider> tokens,
            SecurityCollector collector,
            ObjectProvider<ArtifactSink> sink) {
        var provider = tokens.getIfAvailable();
        return new BusinessPolicyRunner(
                properties,
                business,
                reports,
                provider == null ? java.util.Set.of() : provider.roles(),
                collector,
                sink.getIfAvailable(() -> ArtifactSink.NO_OP));
    }

    @Bean
    @ConditionalOnMissingBean
    public BusinessScenarioGenerator businessScenarioGenerator(
            BusinessScenarioProperties properties,
            SecurityProperties reports,
            ObjectProvider<ArtifactSink> sink) {
        return new BusinessScenarioGenerator(
                properties, reports, sink.getIfAvailable(() -> ArtifactSink.NO_OP));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "forge.security.business",
            name = "enabled",
            havingValue = "true")
    public BusinessSecurityRunner businessSecurityRunner(
            BusinessSecurityProperties properties,
            BusinessPolicyRunner policies,
            io.testforge.http.HttpProperties http,
            ObjectProvider<io.testforge.auth.TokenProvider> tokens,
            ObjectProvider<io.testforge.auth.AuthProperties> auth,
            SecuritySecretRegistry secrets,
            SecurityProperties reports,
            SecurityCollector collector,
            ObjectProvider<ArtifactSink> sink) {
        return new BusinessSecurityRunner(
                properties,
                http,
                tokens.getIfAvailable(),
                auth.getIfAvailable(),
                secrets,
                reports,
                collector,
                sink.getIfAvailable(() -> ArtifactSink.NO_OP),
                policies);
    }

    @Bean
    @ConditionalOnMissingBean
    public ZapEngine zapEngine() {
        return new DockerZapEngine();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "forge.security.active", name = "enabled", havingValue = "true")
    public SecurityActiveRunner securityActiveRunner(
            SecurityActiveProperties p,
            SecurityCollector c,
            ZapEngine engine,
            ObjectProvider<io.testforge.auth.TokenProvider> tokens,
            ObjectProvider<io.testforge.auth.AuthProperties> auth) {
        return new SecurityActiveRunner(
                p, c, engine, tokens.getIfAvailable(), auth.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "forge.security.transport",
            name = "enabled",
            havingValue = "true")
    public SecurityTransportRunner securityTransportRunner(
            SecurityTransportProperties p, SecurityCollector c) {
        return new SecurityTransportRunner(p, c);
    }

    @Bean
    @ConditionalOnMissingBean
    public SecuritySecretRegistry securitySecretRegistry(
            ObjectProvider<io.testforge.auth.AuthProperties> auth) {
        var registry = new SecuritySecretRegistry();
        auth.ifAvailable(
                p -> {
                    registry.register(p.localJwt().secret());
                    p.roles()
                            .values()
                            .forEach(
                                    r -> {
                                        registry.register(r.token());
                                        registry.register(r.clientSecret());
                                        registry.register(r.password());
                                    });
                });
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "forge.security.artifacts-scan",
            name = "enabled",
            havingValue = "true")
    public SecurityArtifactScanner securityArtifactScanner(
            SecurityArtifactProperties p, SecuritySecretRegistry secrets, SecurityCollector c) {
        return new SecurityArtifactScanner(p, secrets, c);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "forge.security.artifacts-scan",
            name = "enabled",
            havingValue = "true")
    public SecurityCredentialCaptureFilter securityCredentialCaptureFilter(
            SecuritySecretRegistry secrets,
            io.testforge.http.HttpProperties http,
            ObjectProvider<io.testforge.auth.AuthProperties> auth) {
        var headers = new java.util.ArrayList<>(http.logging().redactHeaders());
        var query = new java.util.ArrayList<String>();
        auth.ifAvailable(
                p -> {
                    headers.add(p.header());
                    p.roles()
                            .values()
                            .forEach(
                                    r -> {
                                        if (r.type() == io.testforge.auth.TokenType.API_KEY) {
                                            if (r.apiKey().in()
                                                    == io.testforge.auth.AuthProperties
                                                            .ApiKeyProperties.Placement.QUERY)
                                                query.add(r.apiKey().name());
                                            else headers.add(r.apiKey().name());
                                        }
                                    });
                });
        return new SecurityCredentialCaptureFilter(secrets, headers, query);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "forge.security.auth", name = "enabled", havingValue = "true")
    public SecurityAuthRunner securityAuthRunner(
            io.testforge.http.ApiClient api,
            io.testforge.auth.TokenProvider tokens,
            io.testforge.auth.AuthProperties auth,
            SecurityAuthProperties properties,
            SecurityCollector collector) {
        return new SecurityAuthRunner(api, tokens, auth, properties, collector);
    }

    @Bean
    @ConditionalOnMissingBean
    public SecurityCollector securityCollector(SecurityProperties properties) {
        return new SecurityCollector(properties.maxEntries());
    }

    @Bean
    @ConditionalOnMissingBean
    public SecurityRunner securityRunner(
            SecurityProperties p,
            SecurityCollector c,
            ObjectProvider<ArtifactSink> sink,
            org.springframework.core.env.Environment environment) {
        java.util.function.Supplier<java.util.Set<String>> blocks =
                () -> {
                    var enabled =
                            java.util.List.of(
                                            "passive",
                                            "auth",
                                            "transport",
                                            "artifacts-scan",
                                            "active",
                                            "business")
                                    .stream()
                                    .filter(
                                            block ->
                                                    environment.getProperty(
                                                            "forge.security." + block + ".enabled",
                                                            Boolean.class,
                                                            false))
                                    .collect(
                                            java.util.stream.Collectors.toCollection(
                                                    java.util.TreeSet::new));
                    if (environment.getProperty(
                            "forge.security.business.policy.required", Boolean.class, false)) {
                        enabled.add("business");
                        enabled.add("business-policy");
                    }
                    return java.util.Set.copyOf(enabled);
                };
        return new SecurityRunner(
                p,
                c,
                sink.getIfAvailable(() -> ArtifactSink.NO_OP),
                () -> !blocks.get().isEmpty(),
                blocks);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "forge.security.passive",
            name = "enabled",
            havingValue = "true")
    public PassiveSecurityFilter passiveSecurityFilter(SecurityProperties p, SecurityCollector c) {
        return new PassiveSecurityFilter(new PassiveSecurityChecks(p.passive(), c), c);
    }
}
