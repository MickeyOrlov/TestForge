package io.testforge.core.messaging;

/**
 * A broker adapter's own filter type: it reads naturally in tests
 * ({@code KafkaMessageFilter}, {@code RabbitMessageFilter}), describes itself
 * in failure messages through {@code toString()}, and hands
 * {@link MessageProbe} the criteria to match.
 */
public interface MessageQuery {

    MessageCriteria criteria();
}
