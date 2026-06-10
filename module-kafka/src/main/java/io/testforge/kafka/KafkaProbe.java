package io.testforge.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.core.wait.Waiter;
import java.util.Optional;

/**
 * Search-and-wait API over the message buffer.
 *
 * <p>Deliberately knows nothing about payload contracts: contract checks
 * compose on top — await the message here, then validate its value with
 * module-contract's {@code JsonContractValidator}. That keeps module-kafka
 * and module-contract independently deletable.
 */
public class KafkaProbe {

    private final KafkaMessageBuffer buffer;
    private final Waiter waiter;
    private final ObjectMapper objectMapper;

    public KafkaProbe(KafkaMessageBuffer buffer, Waiter waiter, ObjectMapper objectMapper) {
        this.buffer = buffer;
        this.waiter = waiter;
        this.objectMapper = objectMapper;
    }

    public Optional<KafkaMessage> findMessage(KafkaMessageFilter filter) {
        return buffer.newestFirst(filter.topic()).stream()
                .filter(message -> filter.matches(message, objectMapper))
                .findFirst();
    }

    public KafkaMessage awaitMessage(KafkaMessageFilter filter) {
        return waiter.await(
                "Kafka message matching " + filter,
                () -> findMessage(filter).orElse(null),
                ignored -> true);
    }
}
