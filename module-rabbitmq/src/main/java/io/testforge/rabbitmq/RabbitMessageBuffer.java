package io.testforge.rabbitmq;

import io.testforge.core.messaging.MessageBuffer;

/**
 * What the collector received, bounded per exchange; see {@link MessageBuffer}
 * for search order and watermarks.
 */
public class RabbitMessageBuffer extends MessageBuffer<RabbitMessage> {

    public RabbitMessageBuffer(int maxMessagesPerExchange) {
        super(maxMessagesPerExchange);
    }
}
