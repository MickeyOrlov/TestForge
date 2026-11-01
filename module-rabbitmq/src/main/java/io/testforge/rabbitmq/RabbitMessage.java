package io.testforge.rabbitmq;

import io.testforge.core.messaging.BrokerMessage;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A message published to an exchange, as the collector's own queue received
 * it — or as a test published it ({@code delivery} {@code -1}).
 *
 * @param messageId the AMQP {@code message-id} property, or {@code null}
 * @param delivery  the collector's running number for it, {@code -1} for a published message
 */
public record RabbitMessage(
        String exchange,
        String routingKey,
        String value,
        Instant timestamp,
        Map<String, String> headers,
        String messageId,
        long delivery) implements BrokerMessage {

    public RabbitMessage {
        exchange = exchange == null ? "" : exchange;
        if (value == null) {
            value = "";
        }
        if (timestamp == null) {
            timestamp = Instant.EPOCH;
        }
        headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers == null ? Map.of() : headers));
    }

    /** The exchange. */
    @Override
    public String channel() {
        return exchange;
    }

    /** The routing key. */
    @Override
    public String key() {
        return routingKey;
    }

    /** {@code orders/order.paid#7}: exchange, routing key, delivery number. */
    @Override
    public String position() {
        return exchange + "/" + routingKey + (delivery >= 0 ? "#" + delivery : " (published)");
    }
}
