package io.testforge.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.core.wait.WaitProperties;
import io.testforge.core.wait.Waiter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;

class KafkaProbeUnitTest {

    private final KafkaMessageBuffer buffer = new KafkaMessageBuffer(100);
    private final KafkaProbe probe = new KafkaProbe(buffer,
            new Waiter(new WaitProperties(Duration.ofMillis(300), Duration.ofMillis(20))), new ObjectMapper());

    private void publish(String topic, String key, String value, Map<String, String> headers) {
        buffer.append(new KafkaMessage(topic, 0, 0, key, value, null, headers));
    }

    @Test
    void theNewestMatchWins() {
        publish("orders", "o-1", "{\"status\":\"NEW\"}", Map.of());
        publish("orders", "o-1", "{\"status\":\"PAID\"}", Map.of());

        assertThat(probe.findMessage(KafkaMessageFilter.builder().topic("orders").key("o-1").build()))
                .get().extracting(KafkaMessage::value).isEqualTo("{\"status\":\"PAID\"}");
    }

    @Test
    void keysMatchWithOrWithoutJsonQuotes() {
        publish("orders", "\"o-1\"", "{}", Map.of());

        assertThat(probe.findMessage(KafkaMessageFilter.builder().key("o-1").build())).isPresent();
        assertThat(probe.findMessage(KafkaMessageFilter.builder().key(" \"o-1\" ").build())).isPresent();
        assertThat(probe.findMessage(KafkaMessageFilter.builder().key("o-2").build())).isEmpty();
    }

    @Test
    void allCriteriaMustHold() {
        publish("orders", "o-1", "{\"order\":{\"id\":\"o-1\",\"total\":42,\"tags\":[\"vip\"]}}",
                Map.of("type", "OrderCreated"));
        var matching = KafkaMessageFilter.builder()
                .topic("orders")
                .valueContains("total")
                .headerEquals("type", "OrderCreated")
                .jsonPathEquals("$.order.total", "42")
                .jsonPathContains("$.order.tags", "vip");

        assertThat(probe.findMessage(matching.build())).isPresent();
        assertThat(probe.findMessage(matching.headerEquals("type", "OrderDeleted").build())).isEmpty();
    }

    @Test
    void jsonCriteriaNeverMatchNonJsonOrMissingPaths() {
        publish("logs", null, "plain text total=42", Map.of());

        assertThat(probe.findMessage(KafkaMessageFilter.builder().jsonPathEquals("$.total", "42").build())).isEmpty();
        assertThat(probe.findMessage(KafkaMessageFilter.builder().valueContains("total=42").build())).isPresent();

        publish("orders", null, "{\"a\":1}", Map.of());
        assertThat(probe.findMessage(KafkaMessageFilter.builder().jsonPathContains("$.b", "").build())).isEmpty();
    }

    @Test
    void awaitKeepsPollingUntilTheMessageArrives() {
        AtomicInteger polls = new AtomicInteger();
        KafkaMessageBuffer arrivesOnThirdPoll = new KafkaMessageBuffer(10) {
            @Override
            public List<KafkaMessage> newestFirst(String topic) {
                if (polls.incrementAndGet() == 3) {
                    append(new KafkaMessage("orders", 0, 0, "late", "{}", null, Map.of()));
                }
                return super.newestFirst(topic);
            }
        };
        KafkaProbe slowProbe = new KafkaProbe(arrivesOnThirdPoll,
                new Waiter(new WaitProperties(Duration.ofSeconds(2), Duration.ofMillis(10))), new ObjectMapper());

        KafkaMessage message = slowProbe.awaitMessage(KafkaMessageFilter.builder().key("late").build());

        assertThat(message.key()).isEqualTo("late");
        assertThat(polls.get()).isEqualTo(3);
    }

    @Test
    void awaitTimesOutWithTheFilterInTheMessage() {
        assertThatThrownBy(() -> probe.awaitMessage(KafkaMessageFilter.builder().topic("never").build()))
                .isInstanceOf(ConditionTimeoutException.class)
                .hasMessageContaining("never");
    }
}
