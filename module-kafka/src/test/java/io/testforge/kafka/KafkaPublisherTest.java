package io.testforge.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.core.context.ScenarioContext;
import io.testforge.core.context.ScenarioKeys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.mock.MockProducerFactory;

/** A MockProducer stands in for the broker: offline, and every sent record can be read back. */
class KafkaPublisherTest {

    private final MockProducer<String, String> producer =
            new MockProducer<>(true, new StringSerializer(), new StringSerializer());

    private KafkaPublisher publisher(KafkaProbeProperties.PublishProperties properties) {
        return new KafkaPublisher(new KafkaTemplate<>(new MockProducerFactory<>(() -> producer)), new ObjectMapper(),
                properties);
    }

    @AfterEach
    void clear() {
        ScenarioContext.clear();
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    @Test
    void anObjectGoesOutAsJsonWithTheScenariosIdsAndStaticHeaders() {
        ScenarioContext.put(ScenarioKeys.TEST_SCOPE, "scope-1");
        ScenarioContext.put(ScenarioKeys.CORRELATION_ID, "req-1");
        KafkaPublisher publisher = publisher(new KafkaProbeProperties.PublishProperties(
                "X-Test-Scope", "X-Request-Id", Map.of("source", "testforge"), null));

        KafkaMessage sent = publisher.publish("orders.commands", "o-1", Map.of("type", "PAY"));

        ProducerRecord<String, String> record = producer.history().get(0);
        assertThat(record.topic()).isEqualTo("orders.commands");
        assertThat(record.key()).isEqualTo("o-1");
        assertThat(record.value()).isEqualTo("{\"type\":\"PAY\"}");
        assertThat(header(record, "X-Test-Scope")).isEqualTo("scope-1");
        assertThat(header(record, "X-Request-Id")).isEqualTo("req-1");
        assertThat(header(record, "source")).isEqualTo("testforge");
        assertThat(sent.headers()).containsEntry("X-Test-Scope", "scope-1");
        assertThat(sent.offset()).isZero();
    }

    @Test
    void aStringIsSentAsIsAndHeadersGivenToTheCallWin() {
        ScenarioContext.put(ScenarioKeys.TEST_SCOPE, "scope-1");
        KafkaPublisher publisher = publisher(new KafkaProbeProperties.PublishProperties(
                "X-Test-Scope", null, Map.of(), null));

        publisher.publish("orders.commands", "o-1", "{\"raw\":true}", Map.of("X-Test-Scope", "other"));

        ProducerRecord<String, String> record = producer.history().get(0);
        assertThat(record.value()).isEqualTo("{\"raw\":true}");
        assertThat(header(record, "X-Test-Scope")).isEqualTo("other");
    }

    @Test
    void withoutConfiguredHeadersNothingFromTheScenarioIsAdded() {
        ScenarioContext.put(ScenarioKeys.TEST_SCOPE, "scope-1");

        publisher(new KafkaProbeProperties.PublishProperties(null, null, null, null))
                .publish("orders.commands", "o-1", "{}");

        assertThat(producer.history().get(0).headers().toArray()).isEmpty();
    }

    @Test
    void aRejectedSendFailsAtThePublishingLineWithTheTopic() {
        MockProducer<String, String> manual = new MockProducer<>(false, new StringSerializer(), new StringSerializer());
        KafkaPublisher publisher = new KafkaPublisher(new KafkaTemplate<>(new MockProducerFactory<>(() -> manual)),
                new ObjectMapper(), new KafkaProbeProperties.PublishProperties(null, null, null, Duration.ofMillis(200)));

        assertThatThrownBy(() -> publisher.publish("orders.commands", "o-1", "{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Publishing to orders.commands got no acknowledgement within PT0.2S");
    }
}
