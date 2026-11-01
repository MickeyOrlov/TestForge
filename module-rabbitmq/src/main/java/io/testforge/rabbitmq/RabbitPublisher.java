package io.testforge.rabbitmq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import io.testforge.core.context.ScenarioContext;
import io.testforge.core.context.ScenarioKeys;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;

/**
 * Publishes a test's own message, so the flow under test starts the way
 * production starts it — through the exchange, not a back door.
 *
 * <pre>{@code
 * rabbitPublisher.publish("orders", "order.placed", Map.of("orderId", orderId));
 * }</pre>
 *
 * Every message carries the configured static headers and, when
 * {@code forge.rabbitmq.publish.scope-header} / {@code correlation-header}
 * are set, the scenario's scope and correlation ids — the same ids
 * {@code module-http} and {@code module-mock} use. Headers given to the call
 * win over both.
 *
 * <p>The publish is synchronous and mandatory: it waits for the broker's
 * confirm, and a message no queue is bound for fails at the line that sent
 * it, naming exchange and routing key, instead of a later wait timing out.
 */
public class RabbitPublisher {

    static final int PUBLISHED_KEPT = 50;

    private final ConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper;
    private final RabbitProbeProperties.PublishProperties properties;
    private final ThreadLocal<Deque<RabbitMessage>> published = ThreadLocal.withInitial(ArrayDeque::new);

    public RabbitPublisher(ConnectionFactory connectionFactory, ObjectMapper objectMapper,
                           RabbitProbeProperties.PublishProperties properties) {
        this.connectionFactory = connectionFactory;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** {@code payload} as is when it is a String, JSON otherwise. */
    public RabbitMessage publish(String exchange, String routingKey, Object payload) {
        return publish(exchange, routingKey, payload, Map.of());
    }

    public RabbitMessage publish(String exchange, String routingKey, Object payload, Map<String, String> headers) {
        String value = serialize(payload);
        Map<String, String> sent = headers(headers);
        String target = (exchange == null || exchange.isEmpty() ? "the default exchange" : "exchange " + exchange)
                + " with routing key " + routingKey;
        Instant now = Instant.now();
        AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                .contentType(contentType(payload, value))
                .contentEncoding(StandardCharsets.UTF_8.name())
                .headers(new LinkedHashMap<>(sent))
                .messageId(UUID.randomUUID().toString())
                .timestamp(Date.from(now))
                .build();
        AtomicReference<String> returned = new AtomicReference<>();
        Channel channel = connectionFactory.createConnection().createChannel(false);
        try {
            channel.confirmSelect();
            channel.addReturnListener(r -> returned.set(r.getReplyCode() + " " + r.getReplyText()));
            channel.basicPublish(exchange == null ? "" : exchange, routingKey, true, props,
                    value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
            channel.waitForConfirmsOrDie(properties.confirmTimeout().toMillis());
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Publishing to " + target + " failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing to " + target, e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Publishing to " + target + " got no confirm within "
                    + properties.confirmTimeout(), e);
        } finally {
            // never back into Spring's cache in confirm mode, with this publish's return listener on it
            RabbitCollector.closePhysically(channel);
        }
        if (returned.get() != null) {
            throw new IllegalStateException("Publishing to " + target + " was returned (" + returned.get()
                    + "): no queue is bound for it, so no consumer would ever see it");
        }
        return remember(new RabbitMessage(exchange, routingKey, value, now, sent, props.getMessageId(), -1));
    }

    /** Kept for {@link #publishedByThisThread()}, newest last, at most {@value #PUBLISHED_KEPT}. */
    RabbitMessage remember(RabbitMessage message) {
        Deque<RabbitMessage> mine = published.get();
        mine.addLast(message);
        while (mine.size() > PUBLISHED_KEPT) {
            mine.removeFirst();
        }
        return message;
    }

    /** What this thread published since {@link #clearPublished()}, oldest first: for failure diagnostics. */
    public List<RabbitMessage> publishedByThisThread() {
        return List.copyOf(published.get());
    }

    public void clearPublished() {
        published.get().clear();
    }

    /** The configured scope header, or {@code null}: received messages are attributed to a scenario by it. */
    public String scopeHeader() {
        return properties.scopeHeader();
    }

    Map<String, String> headers(Map<String, String> explicit) {
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

    /**
     * {@code application/json} for an object, and for a String that is JSON —
     * a Spring consumer's Jackson converter refuses a body labelled anything
     * without "json"; {@code text/plain} for any other String.
     */
    static String contentType(Object payload, String value) {
        if (payload == null) {
            return "text/plain";
        }
        if (!(payload instanceof String)) {
            return "application/json";
        }
        String text = value.strip();
        return text.startsWith("{") && text.endsWith("}") || text.startsWith("[") && text.endsWith("]")
                ? "application/json" : "text/plain";
    }

    private String serialize(Object payload) {
        if (payload == null || payload instanceof String) {
            return (String) payload;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Payload of type " + payload.getClass().getName()
                    + " cannot be written as JSON: " + e.getOriginalMessage(), e);
        }
    }
}
