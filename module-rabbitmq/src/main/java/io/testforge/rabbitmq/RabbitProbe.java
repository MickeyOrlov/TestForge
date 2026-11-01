package io.testforge.rabbitmq;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.core.messaging.MessageProbe;
import io.testforge.core.wait.Waiter;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Search-and-wait over what the collector received: a message arrived,
 * exactly one arrived, none arrived within a window.
 *
 * <p>Knows nothing about payload contracts: await the message here, then
 * validate its value with module-contract. Failure messages name how close
 * the exchange's messages came — routing key, delivery number and which
 * criteria failed — and never print a body. The waiting itself is the
 * broker-neutral {@link MessageProbe}.
 */
public class RabbitProbe {

    private final MessageProbe<RabbitMessage> probe;

    public RabbitProbe(RabbitMessageBuffer buffer, Waiter waiter, ObjectMapper objectMapper) {
        this.probe = new MessageProbe<>(buffer, waiter, objectMapper, "RabbitMQ",
                exchange -> "Exchange " + exchange + " had no messages in the buffer — is it in "
                        + "forge.rabbitmq.bindings, with a routing key that matches?");
    }

    public Optional<RabbitMessage> findMessage(RabbitMessageFilter filter) {
        return probe.findMessage(filter);
    }

    /** Every buffered message that matches, newest first. */
    public List<RabbitMessage> findMessages(RabbitMessageFilter filter) {
        return probe.findMessages(filter);
    }

    public RabbitMessage awaitMessage(RabbitMessageFilter filter) {
        return probe.awaitMessage(filter);
    }

    /**
     * Waits for the matching message, then watches {@code settle} longer and
     * fails if a second one arrives: the duplicate a redelivery or a retrying
     * publisher sends.
     */
    public RabbitMessage awaitExactlyOne(RabbitMessageFilter filter, Duration settle) {
        return probe.awaitExactlyOne(filter, settle);
    }

    /**
     * Fails if a matching message is buffered now or arrives within
     * {@code window}: the event that must not be sent.
     */
    public void assertNoMessage(RabbitMessageFilter filter, Duration window) {
        probe.assertNoMessage(filter, window);
    }
}
