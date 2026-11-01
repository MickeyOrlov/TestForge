package io.testforge.gherkin.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.en.When;
import io.cucumber.spring.CucumberContextConfiguration;
import io.testforge.artifact.ArtifactSink;
import io.testforge.artifact.TestArtifact;
import io.testforge.core.TestForgeCoreAutoConfiguration;
import io.testforge.core.context.ScenarioContext;
import io.testforge.core.context.ScenarioKeys;
import io.testforge.gherkin.TestForgeGherkinAutoConfiguration;
import io.testforge.kafka.KafkaMessage;
import io.testforge.kafka.KafkaMessageBuffer;
import io.testforge.kafka.TestForgeKafkaAutoConfiguration;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.mock.MockProducerFactory;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

/** The Spring context and one step of {@code diagnostics/failing.feature}; run only by ScenarioDiagnosticsTest. */
@CucumberContextConfiguration
@ContextConfiguration(classes = DiagnosticsFixture.Config.class)
@TestPropertySource(properties = {"forge.kafka.publish.scope-header=X-Test-Scope", "forge.wait.timeout=300ms",
        "forge.gherkin.scope-messages=true",
        "forge.wait.poll-interval=20ms"})
public class DiagnosticsFixture {

    public static final List<String> WRITTEN = new CopyOnWriteArrayList<>();

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({TestForgeCoreAutoConfiguration.class, TestForgeGherkinAutoConfiguration.class,
            TestForgeKafkaAutoConfiguration.class})
    static class Config {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        KafkaTemplate<String, String> kafkaTemplate() {
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
                    WRITTEN.add(source + "/" + category + "/" + name + "\n" + content);
                    return ArtifactSink.NO_OP.write(source, category, name, mediaType, content);
                }
            };
        }
    }

    private final KafkaMessageBuffer buffer;

    public DiagnosticsFixture(KafkaMessageBuffer buffer) {
        this.buffer = buffer;
    }

    @When("the payment service answers for this scenario and for another one")
    public void answers() {
        String scope = ScenarioContext.get(ScenarioKeys.TEST_SCOPE);
        buffer.append(new KafkaMessage("orders.events", 0, 3, "o-1", "{\"status\":\"REJECTED\"}", null,
                Map.of("X-Test-Scope", scope)));
        buffer.append(new KafkaMessage("orders.events", 0, 4, "o-9", "{\"status\":\"PAID\"}", null,
                Map.of("X-Test-Scope", "someone-else")));
    }
}
