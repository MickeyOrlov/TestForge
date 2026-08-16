package io.testforge.mock;

import com.github.tomakehurst.wiremock.client.WireMock;
import io.testforge.artifact.ArtifactSink;
import java.net.URI;
import org.springframework.beans.factory.ObjectProvider;
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
    public ScopedMockClient scopedMockClient(MockProperties properties, ObjectProvider<ArtifactSink> artifactSinkProvider) {
        URI uri = URI.create(properties.baseUrl());
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
        int port = uri.getPort() != -1 ? uri.getPort() : ("https".equals(scheme) ? 443 : 80);

        WireMock wireMock = WireMock.create()
                .scheme(scheme)
                .host(uri.getHost())
                .port(port)
                .build();

        ArtifactSink artifactSink = artifactSinkProvider.getIfAvailable(() -> ArtifactSink.NO_OP);
        return new ScopedMockClient(wireMock, properties.scopeJsonPath(), artifactSink);
    }
}
