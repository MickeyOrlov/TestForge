package io.testforge.kafka;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

public class KafkaMessageBuffer {

    private final int maxMessagesPerTopic;
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentMap<String, ConcurrentSkipListMap<Long, KafkaMessage>> byTopic =
            new ConcurrentHashMap<>();

    public KafkaMessageBuffer(int maxMessagesPerTopic) {
        if (maxMessagesPerTopic <= 0) {
            throw new IllegalArgumentException("maxMessagesPerTopic must be positive");
        }
        this.maxMessagesPerTopic = maxMessagesPerTopic;
    }

    public long append(KafkaMessage message) {
        long nextSequence = sequence.incrementAndGet();
        ConcurrentSkipListMap<Long, KafkaMessage> topicMessages =
                byTopic.computeIfAbsent(message.topic(), ignored -> new ConcurrentSkipListMap<>());

        topicMessages.put(nextSequence, message);
        trim(topicMessages);
        return nextSequence;
    }

    public List<KafkaMessage> messages(String topic) {
        ConcurrentSkipListMap<Long, KafkaMessage> topicMessages = byTopic.get(topic);
        if (topicMessages == null) {
            return List.of();
        }
        return List.copyOf(topicMessages.values());
    }

    public List<KafkaMessage> newestFirst(String topic) {
        if (topic != null && !topic.isBlank()) {
            ConcurrentSkipListMap<Long, KafkaMessage> topicMessages = byTopic.get(topic);
            if (topicMessages == null) {
                return List.of();
            }
            return List.copyOf(topicMessages.descendingMap().values());
        }

        List<SequencedMessage> messages = new ArrayList<>();
        for (ConcurrentSkipListMap<Long, KafkaMessage> topicMessages : byTopic.values()) {
            for (Map.Entry<Long, KafkaMessage> entry : topicMessages.entrySet()) {
                messages.add(new SequencedMessage(entry.getKey(), entry.getValue()));
            }
        }
        messages.sort(Comparator.comparingLong(SequencedMessage::sequence).reversed());
        return messages.stream()
                .map(SequencedMessage::message)
                .toList();
    }

    public void clear() {
        byTopic.clear();
    }

    private void trim(ConcurrentSkipListMap<Long, KafkaMessage> topicMessages) {
        while (topicMessages.size() > maxMessagesPerTopic) {
            topicMessages.pollFirstEntry();
        }
    }

    private record SequencedMessage(long sequence, KafkaMessage message) {
    }
}
