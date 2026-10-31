package io.testforge.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.artifact.ArtifactSink;
import io.testforge.artifact.TestArtifact;
import io.testforge.core.TestForgeCoreAutoConfiguration;
import io.testforge.core.context.ScenarioContext;
import io.testforge.core.context.ScenarioKeys;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.mock.MockProducerFactory;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/** Run only by {@link KafkaDiagnosticsExtensionTest} through EngineTestKit; one test fails on purpose. */
@SpringJUnitConfig(KafkaDiagnosticsTestkitFixture.Config.class)
@TestPropertySource(properties = "forge.kafka.publish.scope-header=X-Test-Scope")
@ExtendWith(KafkaDiagnosticsExtension.class)
class KafkaDiagnosticsTestkitFixture {

    static final List<String> WRITTEN = new CopyOnWriteArrayList<>();

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({TestForgeCoreAutoConfiguration.class, TestForgeKafkaAutoConfiguration.class})
    static class Config {

        @Bean
        com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper();
        }

        @Bean
        KafkaTemplate<String, String> kafkaTemplate() {
            // a fresh producer per send: the template closes each one after use
            return new KafkaTemplate<>(new MockProducerFactory<>(
                    () -> new MockProducer<>(true, new StringSerializer(), new StringSerializer())));
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
    KafkaPublisher publisher;

    @Autowired
    KafkaMessageBuffer buffer;

    @Test
    void failsAfterPublishingAndReceiving() {
        ScenarioContext.put(ScenarioKeys.TEST_SCOPE, "scope-mine");
        publisher.publish("orders.commands", "o-1", Map.of("type", "PAY", "card", "4111"));
        buffer.append(new KafkaMessage("orders.events", 0, 3, "o-1", "{\"status\":\"REJECTED\"}", null,
                Map.of("X-Test-Scope", "scope-mine")));
        buffer.append(new KafkaMessage("orders.events", 0, 4, "o-9", "{}", null, Map.of("X-Test-Scope", "other")));
        assertThat("REJECTED").as("the order status").isEqualTo("PAID");
    }

    @Test
    void passes() {
        publisher.publish("orders.commands", "o-2", "{}");
    }
}
