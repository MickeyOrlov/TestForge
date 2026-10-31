package io.testforge.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.core.wait.WaitProperties;
import io.testforge.core.wait.Waiter;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;

class KafkaProbeAssertionsTest {

    private final KafkaMessageBuffer buffer = new KafkaMessageBuffer(100);
    private final KafkaProbe probe = new KafkaProbe(buffer,
            new Waiter(new WaitProperties(Duration.ofMillis(300), Duration.ofMillis(20))), new ObjectMapper());
    private final AtomicLong offsets = new AtomicLong();

    private void publish(String key, String value, Map<String, String> headers) {
        buffer.append(new KafkaMessage("orders", 0, offsets.getAndIncrement(), key, value, null, headers));
    }

    private static KafkaMessageFilter paid(String orderId) {
        return KafkaMessageFilter.builder().topic("orders").key(orderId)
                .headerEquals("X-Scope", "s-1").jsonPathEquals("$.status", "PAID").build();
    }

    @Test
    void aTimeoutSaysHowCloseTheMessagesCameWithoutPrintingPayloads() {
        publish("o-1", "{\"status\":\"NEW\",\"card\":\"4111\"}", Map.of("X-Scope", "s-1"));
        publish("o-2", "{\"status\":\"PAID\"}", Map.of("X-Scope", "s-2"));
        publish("o-3", "not json", Map.of());

        assertThatThrownBy(() -> probe.awaitMessage(paid("o-1")))
                .isInstanceOf(ConditionTimeoutException.class)
                .hasMessageContaining("3 messages buffered on orders; closest:")
                .hasMessageContaining("orders/0@0 key=o-1 — failed: $.status")
                .hasMessageContaining("orders/0@1 key=o-2 — failed: key, header X-Scope")
                .hasMessageNotContaining("4111");
    }

    @Test
    void aTimeoutOnAnEmptyTopicPointsAtTheConfiguration() {
        assertThatThrownBy(() -> probe.awaitMessage(paid("o-1")))
                .hasMessageContaining("Topic orders had no messages in the buffer — is it in forge.kafka.topics?");
    }

    @Test
    void exactlyOnePassesForASingleMessageAndCatchesTheDuplicate() {
        publish("o-1", "{\"status\":\"PAID\"}", Map.of("X-Scope", "s-1"));

        assertThat(probe.awaitExactlyOne(paid("o-1"), Duration.ofMillis(200)).offset()).isZero();

        CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS)
                .execute(() -> publish("o-1", "{\"status\":\"PAID\"}", Map.of("X-Scope", "s-1")));
        assertThatThrownBy(() -> probe.awaitExactlyOne(paid("o-1"), Duration.ofMillis(300)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Expected exactly one Kafka message")
                .hasMessageContaining("got 2: orders/0@1 key=o-1, orders/0@0 key=o-1");
    }

    @Test
    void noMessagePassesWhenNothingMatchesInTheWindow() {
        publish("o-1", "{\"status\":\"NEW\"}", Map.of("X-Scope", "s-1"));

        probe.assertNoMessage(paid("o-1"), Duration.ofMillis(200));
    }

    @Test
    void noMessageFailsWhenTheForbiddenEventArrivesLate() {
        CompletableFuture.delayedExecutor(80, TimeUnit.MILLISECONDS)
                .execute(() -> publish("o-1", "{\"status\":\"PAID\"}", Map.of("X-Scope", "s-1")));

        assertThatThrownBy(() -> probe.assertNoMessage(paid("o-1"), Duration.ofMillis(300)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Expected no Kafka message")
                .hasMessageContaining("got 1: orders/0@0 key=o-1");
    }
}
