package io.testforge.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.artifact.ArtifactSink;
import io.testforge.artifact.TestArtifact;
import io.testforge.core.TestForgeCoreAutoConfiguration;
import io.testforge.core.context.ScenarioContext;
import io.testforge.core.context.ScenarioKeys;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/** Run only by {@link RabbitDiagnosticsExtensionTest} through EngineTestKit; one test fails on purpose. */
@SpringJUnitConfig(RabbitDiagnosticsTestkitFixture.Config.class)
@TestPropertySource(properties = "forge.rabbitmq.publish.scope-header=X-Test-Scope")
@ExtendWith(RabbitDiagnosticsExtension.class)
class RabbitDiagnosticsTestkitFixture {

    static final List<String> WRITTEN = new CopyOnWriteArrayList<>();

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({TestForgeCoreAutoConfiguration.class, TestForgeRabbitAutoConfiguration.class})
    static class Config {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        /** No broker: a publish is remembered as if the broker had confirmed it. */
        @Bean
        RabbitPublisher rabbitPublisher(ObjectMapper objectMapper, RabbitProbeProperties properties) {
            return new RabbitPublisher(null, objectMapper, properties.publish()) {
                @Override
                public RabbitMessage publish(String exchange, String routingKey, Object payload,
                                             Map<String, String> headers) {
                    return remember(new RabbitMessage(exchange, routingKey, String.valueOf(payload), Instant.EPOCH,
                            headers(headers), "m-1", -1));
                }
            };
        }

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

    @Autowired
    RabbitPublisher publisher;

    @Autowired
    RabbitMessageBuffer buffer;

    @Test
    void failsAfterPublishingAndReceiving() {
        ScenarioContext.put(ScenarioKeys.TEST_SCOPE, "scope-mine");
        publisher.publish("orders", "order.pay", Map.of("card", "4111"));
        buffer.append(new RabbitMessage("payments", "payment.rejected", "{\"status\":\"REJECTED\"}", null,
                Map.of("X-Test-Scope", "scope-mine"), "m-2", 7));
        buffer.append(new RabbitMessage("payments", "payment.captured", "{}", null,
                Map.of("X-Test-Scope", "other"), "m-3", 8));
        assertThat("REJECTED").as("the payment status").isEqualTo("CAPTURED");
    }

    @Test
    void passes() {
        publisher.publish("orders", "order.pay", "{}");
    }
}
