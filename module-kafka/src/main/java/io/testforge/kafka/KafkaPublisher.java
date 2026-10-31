package io.testforge.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.core.context.ScenarioContext;
import io.testforge.core.context.ScenarioKeys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.kafka.core.KafkaOperations;

/**
 * Publishes a test's own event, so the flow under test starts the way
 * production starts it — through the topic, not a back door.
 *
 * <pre>{@code
 * KafkaMessage sent = kafkaPublisher.publish("orders.commands", orderId,
 *         Map.of("type", "PAY", "orderId", orderId));
 * }</pre>
 *
 * Every event carries the configured static headers and, when
 * {@code forge.kafka.publish.scope-header} / {@code correlation-header} are
 * set, the scenario's scope and correlation ids from {@link ScenarioContext} —
 * the same ids {@code module-http} and {@code module-mock} use, so a consumer
 * that forwards them lands on the scenario's scoped stubs. Headers given to
 * the call win over both.
 *
 * <p>The send is synchronous: a rejected event fails the test at the line
 * that published it, with the topic named, instead of a later wait timing
 * out.
 */
public class KafkaPublisher {

    private final KafkaOperations<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final KafkaProbeProperties.PublishProperties properties;

    public KafkaPublisher(KafkaOperations<String, String> kafka, ObjectMapper objectMapper,
                          KafkaProbeProperties.PublishProperties properties) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** {@code payload} as is when it is a String, JSON otherwise. */
    public KafkaMessage publish(String topic, String key, Object payload) {
        return publish(topic, key, payload, Map.of());
    }

    public KafkaMessage publish(String topic, String key, Object payload, Map<String, String> headers) {
        String value = serialize(payload);
        Map<String, String> sent = headers(headers);
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
        sent.forEach((name, header) -> record.headers().add(name, header.getBytes(StandardCharsets.UTF_8)));
        RecordMetadata metadata;
        try {
            metadata = kafka.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .getRecordMetadata();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing to " + topic, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Publishing to " + topic + " failed: " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("Publishing to " + topic + " got no acknowledgement within "
                    + properties.sendTimeout(), e);
        }
        Instant timestamp = metadata.hasTimestamp() ? Instant.ofEpochMilli(metadata.timestamp()) : Instant.now();
        return new KafkaMessage(topic, metadata.partition(), metadata.hasOffset() ? metadata.offset() : -1,
                key, value, timestamp, sent);
    }

    private Map<String, String> headers(Map<String, String> explicit) {
        Map<String, String> headers = new LinkedHashMap<>(properties.headers());
        if (properties.scopeHeader() != null && !properties.scopeHeader().isBlank()) {
            ScenarioContext.find(ScenarioKeys.TEST_SCOPE).ifPresent(scope -> headers.put(properties.scopeHeader(), scope));
        }
        if (properties.correlationHeader() != null && !properties.correlationHeader().isBlank()) {
            ScenarioContext.find(ScenarioKeys.CORRELATION_ID)
                    .ifPresent(id -> headers.put(properties.correlationHeader(), id));
        }
        headers.putAll(explicit == null ? Map.of() : explicit);
        return headers;
    }

    private String serialize(Object payload) {
        if (payload == null) {
            return null;
        }
        if (payload instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Payload of type " + payload.getClass().getName()
                    + " cannot be written as JSON: " + e.getOriginalMessage(), e);
        }
    }
}
