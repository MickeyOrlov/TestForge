package io.testforge.rabbitmq;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RabbitMQ probe settings. The connection itself is Spring Boot's
 * ({@code spring.rabbitmq.*}). The collector is off by default: enable it in
 * the environment profile that has broker access.
 *
 * <pre>
 * forge:
 *   rabbitmq:
 *     enabled: true
 *     bindings:                     # what the collector's own queue receives
 *       - exchange: orders          # a topic exchange: every routing key
 *       - exchange: payments
 *         routing-key: payment.*    # only these
 *     max-messages-per-exchange: 1000
 *     publish:
 *       scope-header: X-Test-Scope        # scenario scope id on every published message
 *       correlation-header: X-Request-Id  # scenario correlation id
 *       headers:                          # sent on every published message
 *         source: testforge
 *       confirm-timeout: 10s
 * </pre>
 */
@ConfigurationProperties(prefix = "forge.rabbitmq")
public record RabbitProbeProperties(
        boolean enabled,
        List<Binding> bindings,
        int maxMessagesPerExchange,
        PublishProperties publish) {

    public RabbitProbeProperties {
        bindings = bindings == null ? List.of() : bindings.stream()
                .filter(binding -> binding != null && binding.exchange() != null && !binding.exchange().isBlank())
                .toList();
        if (maxMessagesPerExchange <= 0) {
            maxMessagesPerExchange = 1_000;
        }
        if (publish == null) {
            publish = new PublishProperties(null, null, null, null);
        }
    }

    /**
     * One binding of the collector's queue. {@code #} — the default — is every
     * routing key on a topic exchange; a direct exchange needs the exact key,
     * a fanout exchange ignores it. Headers exchanges are not supported.
     */
    public record Binding(String exchange, String routingKey) {

        public Binding {
            if (routingKey == null || routingKey.isBlank()) {
                routingKey = "#";
            }
        }
    }

    /** What {@link RabbitPublisher} adds to every message a test publishes. */
    public record PublishProperties(String scopeHeader, String correlationHeader, Map<String, String> headers,
                                    Duration confirmTimeout) {

        public PublishProperties {
            headers = Map.copyOf(headers == null ? Map.of() : headers);
            if (confirmTimeout == null || confirmTimeout.isZero() || confirmTimeout.isNegative()) {
                confirmTimeout = Duration.ofSeconds(10);
            }
        }
    }
}
