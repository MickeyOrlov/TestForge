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
 *     publish:
 *       scope-header: X-Test-Scope        # scenario scope id on every published event
 *       correlation-header: X-Request-Id  # scenario correlation id
 *       headers:                          # sent on every published event
 *         source: testforge
 *       send-timeout: 10s
 *     unique-group-id: true   # default: every run reads every partition (see below)
 *     diagnostics:
 *       last: 20          # messages per failed test
 *       payloads: false   # default: positions, keys and header names only
 * </pre>
 */
/*
 * unique-group-id: two runs sharing spring.kafka.consumer.group-id split the
 * topic's partitions between them, and each sees only part of the messages.
 * The collector only observes, it never needs committed offsets, so by
 * default it joins a group of its own per run.
 */
@ConfigurationProperties(prefix = "forge.kafka")
public record KafkaProbeProperties(
        boolean enabled,
        List<String> topics,
        Duration pollTimeout,
        int maxMessagesPerTopic,
        boolean seekToBeginningOnStart,
        PublishProperties publish,
        DiagnosticsProperties diagnostics,
        Boolean uniqueGroupId) {

    public KafkaProbeProperties {
        if (publish == null) {
            publish = new PublishProperties(null, null, null, null);
        }
        if (uniqueGroupId == null) {
            uniqueGroupId = true;
        }
        if (diagnostics == null) {
            diagnostics = new DiagnosticsProperties(null, null);
        }
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

    /** What {@link KafkaPublisher} adds to every event a test publishes. */
    public record PublishProperties(String scopeHeader, String correlationHeader,
                                    java.util.Map<String, String> headers, Duration sendTimeout) {

        public PublishProperties {
            headers = java.util.Map.copyOf(headers == null ? java.util.Map.of() : headers);
            if (sendTimeout == null || sendTimeout.isZero() || sendTimeout.isNegative()) {
                sendTimeout = Duration.ofSeconds(10);
            }
        }
    }

    /**
     * What a failed test's Kafka report shows. Payloads stay out unless asked
     * for: an event body is as likely to carry personal data as an HTTP one,
     * and this module has no redactor.
     */
    public record DiagnosticsProperties(Integer last, Boolean payloads) {

        public DiagnosticsProperties {
            if (last == null || last <= 0) {
                last = 20;
            }
            if (payloads == null) {
                payloads = false;
            }
        }
    }
}
