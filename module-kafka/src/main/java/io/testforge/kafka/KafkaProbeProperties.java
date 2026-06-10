package io.testforge.kafka;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kafka probe settings. The collector is disabled by default: scheduled drift
 * checks should enable it explicitly in the environment profile that has Kafka
 * access.
 *
 * <pre>
 * forge:
 *   kafka:
 *     enabled: true
 *     topics:
 *       - partner.events
 *     poll-timeout: 500ms
 *     max-messages-per-topic: 500
 * </pre>
 */
@ConfigurationProperties(prefix = "forge.kafka")
public record KafkaProbeProperties(
        boolean enabled,
        List<String> topics,
        Duration pollTimeout,
        int maxMessagesPerTopic,
        boolean seekToBeginningOnStart) {

    public KafkaProbeProperties {
        if (topics == null) {
            topics = List.of();
        }
        topics = topics.stream()
                .filter(topic -> topic != null && !topic.isBlank())
                .toList();
        if (pollTimeout == null) {
            pollTimeout = Duration.ofMillis(500);
        }
        if (maxMessagesPerTopic <= 0) {
            maxMessagesPerTopic = 1_000;
        }
    }
}
