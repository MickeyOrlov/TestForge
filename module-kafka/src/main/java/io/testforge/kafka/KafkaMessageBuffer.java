package io.testforge.kafka;

import io.testforge.core.messaging.MessageBuffer;

/**
 * What the collector received, bounded per topic; see {@link MessageBuffer}
 * for search order and watermarks.
 */
public class KafkaMessageBuffer extends MessageBuffer<KafkaMessage> {

    public KafkaMessageBuffer(int maxMessagesPerTopic) {
        super(maxMessagesPerTopic);
    }
}
