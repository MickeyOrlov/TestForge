package io.testforge.core.messaging;

import java.time.Instant;
import java.util.Map;

/**
 * One message a broker adapter collected, in the terms every broker shares.
 * Adapters keep their own record with the broker's coordinates (Kafka's
 * partition and offset, RabbitMQ's exchange and delivery) and expose them
 * through {@link #position()}.
 */
public interface BrokerMessage {

    /** Where it was published: a Kafka topic, a RabbitMQ exchange. */
    String channel();

    /** A Kafka record key, a RabbitMQ routing key; may be {@code null}. */
    String key();

    /** The payload as text, never {@code null}. */
    String value();

    Instant timestamp();

    Map<String, String> headers();

    /** Where to find it on the broker, without the payload: {@code orders/0@42}. */
    String position();
}
