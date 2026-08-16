package io.testforge.mock;

import io.testforge.artifact.ArtifactSink;
import io.testforge.artifact.TestArtifact;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestForgeMockAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TestForgeMockAutoConfiguration.class));

    @Test
    void configuresScopedMockClientWhenBaseUrlSetWithoutArtifactSinkBean() {
        contextRunner
                .withPropertyValues("forge.mock.base-url=http://localhost:8080")
                .run(context -> {
                    assertThat(context).hasSingleBean(ScopedMockClient.class);
                });
    }

    @Test
    void configuresScopedMockClientWithCustomArtifactSinkWhenPresent() {
        contextRunner
                .withUserConfiguration(CustomSinkConfiguration.class)
                .withPropertyValues("forge.mock.base-url=http://localhost:8080")
                .run(context -> {
                    assertThat(context).hasSingleBean(ScopedMockClient.class);
                    assertThat(context).hasSingleBean(ArtifactSink.class);
                });
    }

    @Configuration
    static class CustomSinkConfiguration {
        @Bean
        public ArtifactSink artifactSink() {
            return new ArtifactSink() {
                @Override
                public Path directoryFor(String source) {
                    return Path.of(System.getProperty("java.io.tmpdir"));
                }

                @Override
                public void register(TestArtifact artifact) {}

                @Override
                public TestArtifact write(String source, String category, String name, String mediaType, String content) {
                    return new TestArtifact(source, category, name, directoryFor(source).resolve(name), mediaType, Instant.now(), Map.of());
                }
            };
        }
    }
}
