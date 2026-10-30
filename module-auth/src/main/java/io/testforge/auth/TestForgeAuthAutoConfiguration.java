package io.testforge.auth;

import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Inert unless {@code forge.auth.enabled=true}: a template must not start
 * asking an identity provider for tokens just because the jar is present.
 */
@AutoConfiguration
@EnableConfigurationProperties(AuthProperties.class)
@ConditionalOnProperty(prefix = "forge.auth", name = "enabled", havingValue = "true")
public class TestForgeAuthAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TokenProvider tokenProvider(AuthProperties properties) {
        Clock clock = Clock.systemUTC();
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.requestTimeout()).build();
        return new TokenProvider(TokenSources.from(properties, http, clock), clock, properties.refreshSkew());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.restassured.filter.Filter")
    static class RestAssuredAuthConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public AuthFilter authFilter(TokenProvider tokens, AuthProperties properties) {
            return new AuthFilter(tokens, properties);
        }
    }
}
