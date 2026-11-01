package io.testforge.rabbitmq;

import io.testforge.core.messaging.MessageCriteria;
import io.testforge.core.messaging.MessageQuery;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which message a test waits for: the exchange it went to, its routing key,
 * fragments of its body, headers, JSON paths. A criterion left out matches
 * anything.
 *
 * <pre>{@code
 * RabbitMessageFilter.builder()
 *         .exchange("orders")
 *         .routingKey("order.paid")
 *         .jsonPathEquals("$.orderId", orderId)
 *         .build();
 * }</pre>
 */
public record RabbitMessageFilter(
        String exchange,
        String routingKey,
        List<String> valueContains,
        Map<String, String> headerEquals,
        Map<String, String> jsonPathEquals,
        Map<String, String> jsonPathContains) implements MessageQuery {

    public RabbitMessageFilter {
        valueContains = List.copyOf(valueContains == null ? List.of() : valueContains);
        headerEquals = Map.copyOf(headerEquals == null ? Map.of() : headerEquals);
        jsonPathEquals = Map.copyOf(jsonPathEquals == null ? Map.of() : jsonPathEquals);
        jsonPathContains = Map.copyOf(jsonPathContains == null ? Map.of() : jsonPathContains);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public MessageCriteria criteria() {
        return new MessageCriteria(exchange, routingKey, valueContains, headerEquals, jsonPathEquals,
                jsonPathContains, "exchange", "routing key");
    }

    public static final class Builder {
        private String exchange;
        private String routingKey;
        private final List<String> valueContains = new ArrayList<>();
        private final Map<String, String> headerEquals = new LinkedHashMap<>();
        private final Map<String, String> jsonPathEquals = new LinkedHashMap<>();
        private final Map<String, String> jsonPathContains = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder exchange(String exchange) {
            this.exchange = exchange;
            return this;
        }

        public Builder routingKey(String routingKey) {
            this.routingKey = routingKey;
            return this;
        }

        public Builder valueContains(String fragment) {
            this.valueContains.add(fragment);
            return this;
        }

        public Builder headerEquals(String name, String value) {
            this.headerEquals.put(name, value);
            return this;
        }

        public Builder jsonPathEquals(String path, String value) {
            this.jsonPathEquals.put(path, value);
            return this;
        }

        public Builder jsonPathContains(String path, String value) {
            this.jsonPathContains.put(path, value);
            return this;
        }

        public RabbitMessageFilter build() {
            return new RabbitMessageFilter(exchange, routingKey, valueContains, headerEquals, jsonPathEquals,
                    jsonPathContains);
        }
    }
}
