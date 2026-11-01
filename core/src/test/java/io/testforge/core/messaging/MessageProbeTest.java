package io.testforge.core.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.core.wait.WaitProperties;
import io.testforge.core.wait.Waiter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;

/** The probe on a broker of its own, so nothing here leans on Kafka. */
class MessageProbeTest {

    record Note(String channel, String key, String value, Map<String, String> headers, long id)
            implements BrokerMessage {

        @Override
        public Instant timestamp() {
            return Instant.EPOCH;
        }

        @Override
        public String position() {
            return channel + "#" + id;
        }
    }

    record Query(String channel, String key, Map<String, String> jsonPathEquals) implements MessageQuery {

        @Override
        public MessageCriteria criteria() {
            return new MessageCriteria(channel, key, List.of(), Map.of(), jsonPathEquals, Map.of(), "exchange",
                    "routing key");
        }
    }

    private final MessageBuffer<Note> buffer = new MessageBuffer<>(3);
    private final MessageProbe<Note> probe = new MessageProbe<>(buffer,
            new Waiter(new WaitProperties(Duration.ofMillis(300), Duration.ofMillis(20))), new ObjectMapper(),
            "Notes", channel -> "Exchange " + channel + " is not watched");

    @Test
    void theNewestMatchIsFoundAndTheBufferKeepsTheLastFewPerChannel() {
        for (int i = 1; i <= 4; i++) {
            buffer.append(new Note("orders", "o-" + i, "{\"n\":" + i + "}", Map.of(), i));
        }
        buffer.append(new Note("payments", "p-1", "{}", Map.of(), 5));

        assertThat(buffer.messages("orders")).extracting(Note::id).containsExactly(2L, 3L, 4L);
        assertThat(probe.findMessage(new Query("orders", null, Map.of("$.n", "3")))).get()
                .extracting(Note::id).isEqualTo(3L);
        assertThat(buffer.newestFirst(null)).extracting(Note::id).containsExactly(5L, 4L, 3L, 2L);
    }

    @Test
    void aTimeoutNamesTheClosestMessagesInTheBrokersOwnTerms() {
        buffer.append(new Note("orders", "o-1", "{\"status\":\"NEW\"}", Map.of(), 1));

        assertThatThrownBy(() -> probe.awaitMessage(new Query("orders", "o-2", Map.of("$.status", "PAID"))))
                .isInstanceOf(ConditionTimeoutException.class)
                .hasMessageContaining("No Notes message matching")
                .hasMessageContaining("orders#1 key=o-1 — failed: routing key, $.status")
                .hasMessageNotContaining("NEW");
        assertThatThrownBy(() -> probe.awaitMessage(new Query("refunds", null, Map.of())))
                .hasMessageContaining("Exchange refunds is not watched");
    }

    @Test
    void aDuplicateAndAnUnwantedMessageAreCaught() {
        buffer.append(new Note("orders", "o-1", "{}", Map.of(), 1));
        buffer.append(new Note("orders", "o-1", "{}", Map.of(), 2));

        assertThatThrownBy(() -> probe.awaitExactlyOne(new Query("orders", "o-1", Map.of()), Duration.ofMillis(100)))
                .hasMessageContaining("Expected exactly one Notes message")
                .hasMessageContaining("2: orders#2 key=o-1, orders#1 key=o-1");
        assertThatThrownBy(() -> probe.assertNoMessage(new Query("orders", null, Map.of()), Duration.ofMillis(100)))
                .hasMessageContaining("Expected no Notes message");
        probe.assertNoMessage(new Query("refunds", null, Map.of()), Duration.ofMillis(100));
    }

    @Test
    void aCollectorThatStoppedIsTheFirstThingATimeoutSays() {
        buffer.collectorFailed("connection refused");

        assertThat(probe.closest(new Query("orders", null, Map.of())))
                .startsWith("The collector stopped after an error and receives nothing: connection refused.");
    }
}
