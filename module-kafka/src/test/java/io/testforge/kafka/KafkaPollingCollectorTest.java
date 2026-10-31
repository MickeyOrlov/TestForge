package io.testforge.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.core.wait.WaitProperties;
import io.testforge.core.wait.Waiter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;

/** A MockConsumer stands in for the broker; the factory records which group the collector asked for. */
class KafkaPollingCollectorTest {

    private static final TopicPartition PARTITION = new TopicPartition("orders", 0);

    private final List<String> groups = new CopyOnWriteArrayList<>();
    private final MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.LATEST);
    private final KafkaMessageBuffer buffer = new KafkaMessageBuffer(100);
    private KafkaPollingCollector collector;

    private final ConsumerFactory<String, String> factory = new ConsumerFactory<>() {
        @Override
        public Consumer<String, String> createConsumer(String groupId, String clientIdPrefix, String clientIdSuffix,
                                                       Properties properties) {
            groups.add(String.valueOf(groupId));
            return consumer;
        }

        @Override
        public boolean isAutoCommit() {
            return false;
        }

        @Override
        public Map<String, Object> getConfigurationProperties() {
            return Map.of(ConsumerConfig.GROUP_ID_CONFIG, "testforge-ci");
        }
    };

    private KafkaPollingCollector collector(Boolean uniqueGroupId) {
        consumer.schedulePollTask(() -> {
            consumer.rebalance(List.of(PARTITION));
            consumer.updateBeginningOffsets(Map.of(PARTITION, 0L));
            consumer.updateEndOffsets(Map.of(PARTITION, 0L));
        });
        collector = new KafkaPollingCollector(factory, new KafkaProbeProperties(true, List.of("orders"),
                Duration.ofMillis(20), 100, false, null, null, uniqueGroupId), buffer);
        return collector;
    }

    @AfterEach
    void stop() {
        if (collector != null) {
            collector.stop();
        }
    }

    @Test
    void startReturnsOnlyOnceThePartitionsAreAssigned() {
        collector(null).start();

        assertThat(collector.assigned()).isTrue();
        consumer.addRecord(new ConsumerRecord<>("orders", 0, 0, "o-1", "{}"));
        new Waiter(new WaitProperties(Duration.ofSeconds(2), Duration.ofMillis(20)))
                .await("buffered", () -> buffer.messages("orders").isEmpty() ? null : true, ignored -> true);
    }

    @Test
    void everyRunJoinsAGroupOfItsOwnUnlessToldOtherwise() {
        collector(null).start();
        collector.stop();
        collector(false).start();

        assertThat(groups.get(0)).startsWith("testforge-ci-").hasSize("testforge-ci-".length() + 8);
        assertThat(groups.get(1)).isEqualTo("null");
    }

    @Test
    void aCollectorThatDiedSaysSoInEveryLaterTimeout() {
        collector(null).start();
        consumer.setPollException(new KafkaException("broker gone"));

        new Waiter(new WaitProperties(Duration.ofSeconds(2), Duration.ofMillis(20)))
                .await("collector stopped", () -> collector.isRunning() ? null : true, ignored -> true);
        KafkaProbe probe = new KafkaProbe(buffer,
                new Waiter(new WaitProperties(Duration.ofMillis(100), Duration.ofMillis(20))), new ObjectMapper());

        assertThatThrownBy(() -> probe.awaitMessage(KafkaMessageFilter.builder().topic("orders").build()))
                .hasMessageContaining("The collector stopped after an error and receives nothing:")
                .hasMessageContaining("broker gone");
    }
}
