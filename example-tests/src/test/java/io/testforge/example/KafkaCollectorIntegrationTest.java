package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.core.context.ScenarioContext;
import io.testforge.core.context.ScenarioKeys;
import io.testforge.kafka.KafkaMessage;
import io.testforge.kafka.KafkaMessageBuffer;
import io.testforge.kafka.KafkaMessageFilter;
import io.testforge.kafka.KafkaPollingCollector;
import io.testforge.kafka.KafkaProbe;
import io.testforge.kafka.KafkaPublisher;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * Exercises the real collector path — subscription, rebalance, polling,
 * seek-to-beginning — against an embedded broker on a random port. The other
 * Kafka examples stay offline by feeding the buffer directly; this one proves
 * the wiring between a live broker and that buffer.
 */
@SpringBootTest(properties = {
        "forge.kafka.enabled=true",
        "forge.kafka.topics[0]=collector.events",
        "forge.kafka.seek-to-beginning-on-start=true",
        "forge.kafka.poll-timeout=300ms",
        "forge.wait.timeout=30s",
        "spring.kafka.consumer.group-id=testforge-collector-it",
        "forge.kafka.publish.scope-header=X-Test-Scope",
        "forge.kafka.publish.headers.source=testforge"
})
@EmbeddedKafka(
        partitions = 1,
        topics = "collector.events",
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class KafkaCollectorIntegrationTest {

    private static final String TOPIC = "collector.events";

    @Autowired
    KafkaMessageBuffer buffer;

    @Autowired
    KafkaPollingCollector collector;

    @Autowired
    KafkaProbe kafka;

    @Autowired
    EmbeddedKafkaBroker broker;

    @Autowired
    KafkaPublisher publisher;

    @BeforeEach
    void clearBuffer() {
        buffer.clear();
    }

    @Test
    void collectsProducedRecordIntoBuffer() {
        String key = "key-" + UUID.randomUUID();

        produce(key, "{\"status\":\"created\"}");

        KafkaMessage message = kafka.awaitMessage(
                KafkaMessageFilter.builder().topic(TOPIC).key(key).build());

        assertThat(message.value()).contains("created");
    }

    @Test
    void seekToBeginningRereadsExistingRecordsAfterRestart() {
        String key = "key-" + UUID.randomUUID();
        produce(key, "{\"status\":\"historic\"}");

        // make sure the record is consumed (and its offset effectively passed)
        kafka.awaitMessage(KafkaMessageFilter.builder().topic(TOPIC).key(key).build());

        collector.stop();
        buffer.clear();
        collector.start();

        // a fresh consumer with seek-to-beginning must re-read the old record
        KafkaMessage reread = kafka.awaitMessage(
                KafkaMessageFilter.builder().topic(TOPIC).key(key).build());

        assertThat(reread.value()).contains("historic");
    }

    @Test
    void aPublishedTestEventCarriesTheScenarioScopeThroughTheBroker() {
        String key = "key-" + UUID.randomUUID();
        ScenarioContext.put(ScenarioKeys.TEST_SCOPE, "scope-" + key);
        try {
            KafkaMessage sent = publisher.publish(TOPIC, key, java.util.Map.of("status", "requested"));

            KafkaMessage received = kafka.awaitMessage(KafkaMessageFilter.builder().topic(TOPIC).key(key)
                    .headerEquals("X-Test-Scope", "scope-" + key).headerEquals("source", "testforge")
                    .jsonPathEquals("$.status", "requested").build());

            assertThat(received.offset()).isEqualTo(sent.offset());
        } finally {
            ScenarioContext.clear();
        }
    }

    private void produce(String key, String value) {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(
                KafkaTestUtils.producerProps(broker),
                new StringSerializer(),
                new StringSerializer())) {
            producer.send(new ProducerRecord<>(TOPIC, key, value)).get();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to produce test record", e);
        }
    }
}
