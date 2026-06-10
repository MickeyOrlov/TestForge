package io.testforge.mock;

import com.github.tomakehurst.wiremock.client.WireMock;
import java.net.URI;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(MockProperties.class)
@ConditionalOnProperty(prefix = "forge.mock", name = "base-url")
public class TestForgeMockAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ScopedMockClient scopedMockClient(MockProperties properties) {
        URI uri = URI.create(properties.baseUrl());
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
        int port = uri.getPort() != -1 ? uri.getPort() : ("https".equals(scheme) ? 443 : 80);

        WireMock wireMock = WireMock.create()
                .scheme(scheme)
                .host(uri.getHost())
                .port(port)
                .build();

        return new ScopedMockClient(wireMock, properties.scopeJsonPath());
    }
}
