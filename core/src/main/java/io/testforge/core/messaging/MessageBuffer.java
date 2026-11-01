package io.testforge.core.messaging;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What a collector received, bounded per channel, newest first on search.
 * A sequence across channels orders messages as they arrived and gives a
 * watermark for "what arrived while this test ran".
 */
public class MessageBuffer<M extends BrokerMessage> {

    private final int maxMessagesPerChannel;
    private final AtomicLong sequence = new AtomicLong();
    private volatile String collectorFailure;
    private final ConcurrentMap<String, ConcurrentSkipListMap<Long, M>> byChannel = new ConcurrentHashMap<>();

    public MessageBuffer(int maxMessagesPerChannel) {
        if (maxMessagesPerChannel <= 0) {
            throw new IllegalArgumentException("maxMessagesPerChannel must be positive");
        }
        this.maxMessagesPerChannel = maxMessagesPerChannel;
    }

    public long append(M message) {
        long next = sequence.incrementAndGet();
        ConcurrentSkipListMap<Long, M> messages =
                byChannel.computeIfAbsent(message.channel(), ignored -> new ConcurrentSkipListMap<>());
        messages.put(next, message);
        while (messages.size() > maxMessagesPerChannel) {
            messages.pollFirstEntry();
        }
        return next;
    }

    /** The channel's messages, oldest first. */
    public List<M> messages(String channel) {
        ConcurrentSkipListMap<Long, M> messages = byChannel.get(channel);
        return messages == null ? List.of() : List.copyOf(messages.values());
    }

    /** The channel's messages newest first; every channel's when {@code channel} is blank. */
    public List<M> newestFirst(String channel) {
        if (channel != null && !channel.isBlank()) {
            ConcurrentSkipListMap<Long, M> messages = byChannel.get(channel);
            return messages == null ? List.of() : List.copyOf(messages.descendingMap().values());
        }
        List<Sequenced<M>> all = new ArrayList<>();
        for (ConcurrentSkipListMap<Long, M> messages : byChannel.values()) {
            for (Map.Entry<Long, M> entry : messages.entrySet()) {
                all.add(new Sequenced<>(entry.getKey(), entry.getValue()));
            }
        }
        all.sort(Comparator.comparingLong((Sequenced<M> s) -> s.sequence()).reversed());
        return all.stream().map(Sequenced::message).toList();
    }

    /** The sequence of the last appended message: a watermark for {@link #since(long)}. */
    public long sequence() {
        return sequence.get();
    }

    /** Every buffered message appended after {@code watermark}, across channels, oldest first. */
    public List<M> since(long watermark) {
        List<Sequenced<M>> all = new ArrayList<>();
        for (ConcurrentSkipListMap<Long, M> messages : byChannel.values()) {
            messages.tailMap(watermark, false).forEach((key, message) -> all.add(new Sequenced<>(key, message)));
        }
        all.sort(Comparator.comparingLong(Sequenced::sequence));
        return all.stream().map(Sequenced::message).toList();
    }

    /** Set by the collector when it stops on an error, so a timeout can say why nothing arrives. */
    public void collectorFailed(String reason) {
        this.collectorFailure = reason;
    }

    public Optional<String> collectorFailure() {
        return Optional.ofNullable(collectorFailure);
    }

    public void clear() {
        byChannel.clear();
    }

    private record Sequenced<M>(long sequence, M message) {
    }
}
