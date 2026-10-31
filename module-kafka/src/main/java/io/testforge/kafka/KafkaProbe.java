package io.testforge.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.core.wait.Waiter;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;

/**
 * Search-and-wait API over the message buffer.
 *
 * <p>Deliberately knows nothing about payload contracts: contract checks
 * compose on top — await the message here, then validate its value with
 * module-contract's {@code JsonContractValidator}. That keeps module-kafka
 * and module-contract independently deletable.
 *
 * <p>Failure messages name how close the topic's messages came — offset, key
 * and which criteria failed — and never print a payload.
 */
public class KafkaProbe {

    static final int CLOSEST_SHOWN = 5;
    static final Duration POLL = Duration.ofMillis(100);

    private final KafkaMessageBuffer buffer;
    private final Waiter waiter;
    private final ObjectMapper objectMapper;

    public KafkaProbe(KafkaMessageBuffer buffer, Waiter waiter, ObjectMapper objectMapper) {
        this.buffer = buffer;
        this.waiter = waiter;
        this.objectMapper = objectMapper;
    }

    public Optional<KafkaMessage> findMessage(KafkaMessageFilter filter) {
        return findMessages(filter).stream().findFirst();
    }

    /** Every buffered message that matches, newest first. */
    public List<KafkaMessage> findMessages(KafkaMessageFilter filter) {
        return buffer.newestFirst(filter.topic()).stream()
                .filter(message -> filter.matches(message, objectMapper))
                .toList();
    }

    public KafkaMessage awaitMessage(KafkaMessageFilter filter) {
        try {
            return waiter.await(
                    "Kafka message matching " + filter,
                    () -> findMessage(filter).orElse(null),
                    ignored -> true);
        } catch (ConditionTimeoutException e) {
            throw new ConditionTimeoutException("No Kafka message matching " + filter + ". " + closest(filter), e);
        }
    }

    /**
     * Waits for the matching message, then watches {@code settle} longer and
     * fails if a second one arrives: the duplicate a retrying producer sends.
     */
    public KafkaMessage awaitExactlyOne(KafkaMessageFilter filter, Duration settle) {
        KafkaMessage first = awaitMessage(filter);
        try {
            Awaitility.await("no second Kafka message matching " + filter)
                    .during(settle).atMost(settle.plus(POLL.multipliedBy(5))).pollInterval(POLL).pollInSameThread()
                    .until(() -> findMessages(filter).size() <= 1);
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("Expected exactly one Kafka message matching " + filter + ", got "
                    + describe(findMessages(filter)), e);
        }
        return first;
    }

    /**
     * Fails if a matching message is buffered now or arrives within
     * {@code window}: the event that must not be sent — a refund for a
     * rejected order, a notification to the wrong tenant.
     */
    public void assertNoMessage(KafkaMessageFilter filter, Duration window) {
        try {
            Awaitility.await("no Kafka message matching " + filter)
                    .during(window).atMost(window.plus(POLL.multipliedBy(5))).pollInterval(POLL).pollInSameThread()
                    .until(() -> findMessages(filter).isEmpty());
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("Expected no Kafka message matching " + filter + ", got "
                    + describe(findMessages(filter)), e);
        }
    }

    /** The topic's messages that came closest, fewest failed criteria first, then newest. */
    String closest(KafkaMessageFilter filter) {
        List<KafkaMessage> messages = buffer.newestFirst(filter.topic());
        if (messages.isEmpty()) {
            return filter.topic() == null ? "The buffer is empty." : "Topic " + filter.topic()
                    + " had no messages in the buffer — is it in forge.kafka.topics?";
        }
        record Candidate(KafkaMessage message, List<String> failed, int order) {
        }
        List<Candidate> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            candidates.add(new Candidate(messages.get(i), filter.mismatches(messages.get(i), objectMapper), i));
        }
        String shown = candidates.stream()
                .sorted(Comparator.comparingInt((Candidate c) -> c.failed().size()).thenComparingInt(Candidate::order))
                .limit(CLOSEST_SHOWN)
                .map(c -> "\n  - " + position(c.message()) + " — failed: " + String.join(", ", c.failed()))
                .collect(Collectors.joining());
        return messages.size() + " messages buffered" + (filter.topic() == null ? "" : " on " + filter.topic())
                + "; closest:" + shown;
    }

    private static String describe(List<KafkaMessage> messages) {
        return messages.size() + ": " + messages.stream().map(KafkaProbe::position).collect(Collectors.joining(", "));
    }

    private static String position(KafkaMessage message) {
        return message.topic() + "/" + message.partition() + "@" + message.offset() + " key=" + message.key();
    }
}
