package io.testforge.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.core.messaging.MessageCriteria;
import io.testforge.core.messaging.MessageQuery;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record KafkaMessageFilter(
        String topic,
        String key,
        List<String> valueContains,
        Map<String, String> headerEquals,
        Map<String, String> jsonPathEquals,
        Map<String, String> jsonPathContains) implements MessageQuery {

    public KafkaMessageFilter {
        valueContains = List.copyOf(valueContains == null ? List.of() : valueContains);
        headerEquals = Map.copyOf(headerEquals == null ? Map.of() : headerEquals);
        jsonPathEquals = Map.copyOf(jsonPathEquals == null ? Map.of() : jsonPathEquals);
        jsonPathContains = Map.copyOf(jsonPathContains == null ? Map.of() : jsonPathContains);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The criteria {@link io.testforge.core.messaging.MessageProbe} matches, the key named {@code key}. */
    @Override
    public MessageCriteria criteria() {
        return new MessageCriteria(topic, key, valueContains, headerEquals, jsonPathEquals, jsonPathContains,
                "topic", "key");
    }

    boolean matches(KafkaMessage message, ObjectMapper objectMapper) {
        return criteria().matches(message, objectMapper);
    }

    /**
     * The criteria {@code message} fails, by name only — {@code key},
     * {@code header X-Scope}, {@code $.payload.status} — never the values.
     */
    List<String> mismatches(KafkaMessage message, ObjectMapper objectMapper) {
        return criteria().mismatches(message, objectMapper);
    }

    public static final class Builder {
        private String topic;
        private String key;
        private final List<String> valueContains = new ArrayList<>();
        private final Map<String, String> headerEquals = new LinkedHashMap<>();
        private final Map<String, String> jsonPathEquals = new LinkedHashMap<>();
        private final Map<String, String> jsonPathContains = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder topic(String topic) {
            this.topic = topic;
            return this;
        }

        public Builder key(String key) {
            this.key = key;
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

        public KafkaMessageFilter build() {
            return new KafkaMessageFilter(topic, key, valueContains, headerEquals, jsonPathEquals, jsonPathContains);
        }
    }
}
