package io.testforge.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.core.wait.Waiter;
import io.testforge.core.messaging.MessageProbe;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Search-and-wait API over the message buffer.
 *
 * <p>Deliberately knows nothing about payload contracts: contract checks
 * compose on top — await the message here, then validate its value with
 * module-contract's {@code JsonContractValidator}. That keeps module-kafka
 * and module-contract independently deletable.
 *
 * <p>Failure messages name how close the topic's messages came — offset, key
 * and which criteria failed — and never print a payload. The waiting itself
 * is the broker-neutral {@link MessageProbe}.
 */
public class KafkaProbe {

    static final int CLOSEST_SHOWN = MessageProbe.CLOSEST_SHOWN;
    static final Duration POLL = MessageProbe.POLL;

    private final MessageProbe<KafkaMessage> probe;

    public KafkaProbe(KafkaMessageBuffer buffer, Waiter waiter, ObjectMapper objectMapper) {
        this.probe = new MessageProbe<>(buffer, waiter, objectMapper, "Kafka",
                topic -> "Topic " + topic + " had no messages in the buffer — is it in forge.kafka.topics?");
    }

    public Optional<KafkaMessage> findMessage(KafkaMessageFilter filter) {
        return probe.findMessage(filter);
    }

    /** Every buffered message that matches, newest first. */
    public List<KafkaMessage> findMessages(KafkaMessageFilter filter) {
        return probe.findMessages(filter);
    }

    public KafkaMessage awaitMessage(KafkaMessageFilter filter) {
        return probe.awaitMessage(filter);
    }

    /**
     * Waits for the matching message, then watches {@code settle} longer and
     * fails if a second one arrives: the duplicate a retrying producer sends.
     */
    public KafkaMessage awaitExactlyOne(KafkaMessageFilter filter, Duration settle) {
        return probe.awaitExactlyOne(filter, settle);
    }

    /**
     * Fails if a matching message is buffered now or arrives within
     * {@code window}: the event that must not be sent — a refund for a
     * rejected order, a notification to the wrong tenant.
     */
    public void assertNoMessage(KafkaMessageFilter filter, Duration window) {
        probe.assertNoMessage(filter, window);
    }

    /** The topic's messages that came closest, fewest failed criteria first, then newest. */
    String closest(KafkaMessageFilter filter) {
        return probe.closest(filter);
    }
}
