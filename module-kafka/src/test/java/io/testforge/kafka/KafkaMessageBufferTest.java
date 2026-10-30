package io.testforge.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class KafkaMessageBufferTest {

    static KafkaMessage message(String topic, String key, String value) {
        return new KafkaMessage(topic, 0, 0, key, value, null, Map.of());
    }

    @Test
    void keepsOnlyTheNewestMessagesPerTopic() {
        KafkaMessageBuffer buffer = new KafkaMessageBuffer(2);
        buffer.append(message("orders", "1", "a"));
        buffer.append(message("orders", "2", "b"));
        buffer.append(message("orders", "3", "c"));
        buffer.append(message("payments", "9", "z"));

        assertThat(buffer.messages("orders")).extracting(KafkaMessage::value).containsExactly("b", "c");
        assertThat(buffer.messages("payments")).extracting(KafkaMessage::value).containsExactly("z");
    }

    @Test
    void newestFirstAcrossAllTopicsFollowsArrivalOrder() {
        KafkaMessageBuffer buffer = new KafkaMessageBuffer(10);
        buffer.append(message("a", null, "1"));
        buffer.append(message("b", null, "2"));
        buffer.append(message("a", null, "3"));

        assertThat(buffer.newestFirst(null)).extracting(KafkaMessage::value).containsExactly("3", "2", "1");
        assertThat(buffer.newestFirst(" ")).extracting(KafkaMessage::value).containsExactly("3", "2", "1");
        assertThat(buffer.newestFirst("a")).extracting(KafkaMessage::value).containsExactly("3", "1");
        assertThat(buffer.newestFirst("unknown")).isEmpty();
    }

    @Test
    void clearEmptiesEveryTopic() {
        KafkaMessageBuffer buffer = new KafkaMessageBuffer(10);
        buffer.append(message("a", null, "1"));
        buffer.clear();

        assertThat(buffer.newestFirst(null)).isEmpty();
        assertThat(buffer.messages("a")).isEmpty();
    }

    @Test
    void capacityMustBePositive() {
        assertThatThrownBy(() -> new KafkaMessageBuffer(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void messageDefaultsAreSafe() {
        KafkaMessage message = new KafkaMessage("t", 0, 0, null, null, null, null);

        assertThat(message.value()).isEmpty();
        assertThat(message.headers()).isEmpty();
        assertThat(message.timestamp().toEpochMilli()).isZero();
        assertThatThrownBy(() -> new KafkaMessage(" ", 0, 0, null, "v", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
