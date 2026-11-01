package io.testforge.core.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.core.wait.Waiter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;

/**
 * Search and wait over a {@link MessageBuffer}: a message arrived, exactly
 * one arrived, none arrived within a window. A broker adapter wraps it under
 * its own name ({@code KafkaProbe}, {@code RabbitProbe}).
 *
 * <p>Knows nothing about payload contracts — those compose on top. Failure
 * messages name how close the channel's messages came, by position, key and
 * failed criteria, and never print a payload.
 *
 * @param <M> the adapter's message type
 */
public class MessageProbe<M extends BrokerMessage> {

    public static final int CLOSEST_SHOWN = 5;
    public static final Duration POLL = Duration.ofMillis(100);

    private final MessageBuffer<M> buffer;
    private final Waiter waiter;
    private final ObjectMapper objectMapper;
    private final String broker;
    private final Function<String, String> emptyChannelHint;

    /**
     * @param broker           how failures name it: {@code Kafka}, {@code RabbitMQ}
     * @param emptyChannelHint what to say when a channel has no message at all:
     *                         usually which setting makes the collector watch it
     */
    public MessageProbe(MessageBuffer<M> buffer, Waiter waiter, ObjectMapper objectMapper, String broker,
                        Function<String, String> emptyChannelHint) {
        this.buffer = buffer;
        this.waiter = waiter;
        this.objectMapper = objectMapper;
        this.broker = broker;
        this.emptyChannelHint = emptyChannelHint;
    }

    public Optional<M> findMessage(MessageQuery query) {
        return findMessages(query).stream().findFirst();
    }

    /** Every buffered message that matches, newest first. */
    public List<M> findMessages(MessageQuery query) {
        MessageCriteria criteria = query.criteria();
        return buffer.newestFirst(criteria.channel()).stream()
                .filter(message -> criteria.matches(message, objectMapper))
                .toList();
    }

    public M awaitMessage(MessageQuery query) {
        try {
            return waiter.await(broker + " message matching " + query, () -> findMessage(query).orElse(null),
                    ignored -> true);
        } catch (ConditionTimeoutException e) {
            throw new ConditionTimeoutException("No " + broker + " message matching " + query + ". "
                    + closest(query), e);
        }
    }

    /**
     * Waits for the matching message, then watches {@code settle} longer and
     * fails if a second one arrives: the duplicate a retrying producer sends.
     */
    public M awaitExactlyOne(MessageQuery query, Duration settle) {
        M first = awaitMessage(query);
        try {
            Awaitility.await("no second " + broker + " message matching " + query)
                    .during(settle).atMost(settle.plus(POLL.multipliedBy(5))).pollInterval(POLL).pollInSameThread()
                    .until(() -> findMessages(query).size() <= 1);
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("Expected exactly one " + broker + " message matching " + query + ", got "
                    + describe(findMessages(query)), e);
        }
        return first;
    }

    /**
     * Fails if a matching message is buffered now or arrives within
     * {@code window}: the event that must not be sent.
     */
    public void assertNoMessage(MessageQuery query, Duration window) {
        try {
            Awaitility.await("no " + broker + " message matching " + query)
                    .during(window).atMost(window.plus(POLL.multipliedBy(5))).pollInterval(POLL).pollInSameThread()
                    .until(() -> findMessages(query).isEmpty());
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("Expected no " + broker + " message matching " + query + ", got "
                    + describe(findMessages(query)), e);
        }
    }

    /** The channel's messages that came closest, fewest failed criteria first, then newest. */
    public String closest(MessageQuery query) {
        String collector = buffer.collectorFailure()
                .map(reason -> "The collector stopped after an error and receives nothing: " + reason + ". ")
                .orElse("");
        return collector + closestMessages(query.criteria());
    }

    private String closestMessages(MessageCriteria criteria) {
        String channel = criteria.channel();
        List<M> messages = buffer.newestFirst(channel);
        if (messages.isEmpty()) {
            return channel == null ? "The buffer is empty." : emptyChannelHint.apply(channel);
        }
        record Candidate<T>(T message, List<String> failed, int order) {
        }
        List<Candidate<M>> candidates = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            candidates.add(new Candidate<>(messages.get(i), criteria.mismatches(messages.get(i), objectMapper), i));
        }
        String shown = candidates.stream()
                .sorted(Comparator.comparingInt((Candidate<M> c) -> c.failed().size())
                        .thenComparingInt(Candidate::order))
                .limit(CLOSEST_SHOWN)
                .map(c -> "\n  - " + position(c.message()) + " — failed: " + String.join(", ", c.failed()))
                .collect(Collectors.joining());
        return messages.size() + " messages buffered" + (channel == null ? "" : " on " + channel) + "; closest:"
                + shown;
    }

    private String describe(List<M> messages) {
        return messages.size() + ": " + messages.stream().map(this::position).collect(Collectors.joining(", "));
    }

    private String position(M message) {
        return message.position() + " key=" + message.key();
    }
}
