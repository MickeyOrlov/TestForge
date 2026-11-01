package io.testforge.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.artifact.ArtifactSink;
import io.testforge.artifact.TestArtifact;
import io.testforge.core.TestForgeCoreAutoConfiguration;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/** Run only by {@link HttpDiagnosticsExtensionTest} through EngineTestKit; one test fails on purpose. */
@SpringJUnitConfig(HttpDiagnosticsTestkitFixture.Config.class)
@ExtendWith(HttpDiagnosticsExtension.class)
class HttpDiagnosticsTestkitFixture {

    static final List<String> WRITTEN = new CopyOnWriteArrayList<>();
    static volatile int port;

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({TestForgeCoreAutoConfiguration.class, TestForgeHttpAutoConfiguration.class})
    static class Config {

        @Bean
        ArtifactSink recordingSink() {
            return new ArtifactSink() {
                @Override
                public Path directoryFor(String source) {
                    return Path.of("unused");
                }

                @Override
                public void register(TestArtifact artifact) {
                }

                @Override
                public TestArtifact write(String source, String category, String name, String mediaType,
                                          String content) {
                    WRITTEN.add(source + "/" + name + "\n" + content);
                    return ArtifactSink.NO_OP.write(source, category, name, mediaType, content);
                }
            };
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("forge.http.base-url", () -> "http://127.0.0.1:" + port);
        registry.add("forge.http.diagnostics.last", () -> "2");
        // the sink's directory is a placeholder: a run summary would land in the source tree
        registry.add("forge.http.summary.enabled", () -> "false");
    }

    @Autowired
    ApiClient api;

    @Test
    void failsAfterThreeCalls() {
        api.request().get("/first");
        api.request().header("Authorization", "Bearer very-secret")
                .contentType("application/json").body("{\"password\":\"hunter2\",\"name\":\"x\"}")
                .post("/second");
        api.request().get("/third");
        assertThat(1).as("the order total").isEqualTo(2);
    }

    @Test
    void passes() {
        api.request().get("/first");
    }
}
