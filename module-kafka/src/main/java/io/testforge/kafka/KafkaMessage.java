package io.testforge.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

public record KafkaMessage(
        String topic,
        int partition,
        long offset,
        String key,
        String value,
        Instant timestamp,
        Map<String, String> headers) {

    public KafkaMessage {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        if (value == null) {
            value = "";
        }
        if (timestamp == null) {
            timestamp = Instant.EPOCH;
        }
        headers = Collections.unmodifiableMap(new LinkedHashMap<>(
                headers == null ? Map.of() : headers));
    }

    static KafkaMessage from(ConsumerRecord<String, String> record) {
        long timestamp = record.timestamp();
        return new KafkaMessage(
                record.topic(),
                record.partition(),
                record.offset(),
                record.key(),
                record.value(),
                timestamp < 0 ? Instant.EPOCH : Instant.ofEpochMilli(timestamp),
                headers(record));
    }

    private static Map<String, String> headers(ConsumerRecord<String, String> record) {
        Map<String, String> decoded = new LinkedHashMap<>();
        for (Header header : record.headers()) {
            byte[] value = header.value();
            decoded.put(header.key(), value == null ? null : new String(value, StandardCharsets.UTF_8));
        }
        return decoded;
    }
}
