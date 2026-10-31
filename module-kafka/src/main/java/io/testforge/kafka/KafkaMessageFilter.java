package io.testforge.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.core.json.JsonPath;
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
        Map<String, String> jsonPathContains) {

    public KafkaMessageFilter {
        valueContains = List.copyOf(valueContains == null ? List.of() : valueContains);
        headerEquals = Map.copyOf(headerEquals == null ? Map.of() : headerEquals);
        jsonPathEquals = Map.copyOf(jsonPathEquals == null ? Map.of() : jsonPathEquals);
        jsonPathContains = Map.copyOf(jsonPathContains == null ? Map.of() : jsonPathContains);
    }

    public static Builder builder() {
        return new Builder();
    }

    boolean matches(KafkaMessage message, ObjectMapper objectMapper) {
        return mismatches(message, objectMapper).isEmpty();
    }

    /**
     * The criteria {@code message} fails, by name only — {@code key},
     * {@code header X-Scope}, {@code $.payload.status} — never the values, so a
     * timeout can say how close a message came without printing its payload.
     */
    List<String> mismatches(KafkaMessage message, ObjectMapper objectMapper) {
        List<String> failed = new ArrayList<>();
        if (topic != null && !topic.equals(message.topic())) {
            failed.add("topic");
            return failed;
        }
        if (key != null && !normalize(key).equals(normalize(message.key()))) {
            failed.add("key");
        }
        for (int i = 0; i < valueContains.size(); i++) {
            if (!message.value().contains(valueContains.get(i))) {
                failed.add("value contains #" + (i + 1));
            }
        }
        for (Map.Entry<String, String> expectedHeader : new java.util.TreeMap<>(headerEquals).entrySet()) {
            if (!expectedHeader.getValue().equals(message.headers().get(expectedHeader.getKey()))) {
                failed.add("header " + expectedHeader.getKey());
            }
        }
        if (!jsonPathEquals.isEmpty() || !jsonPathContains.isEmpty()) {
            JsonNode root = readJson(message, objectMapper);
            if (root == null) {
                failed.add("value is not JSON");
                return failed;
            }
            new java.util.TreeMap<>(jsonPathEquals).forEach((path, expected) -> {
                JsonNode value = JsonPath.read(root, path);
                if (value.isMissingNode() || !expected.equals(text(value))) {
                    failed.add(path);
                }
            });
            new java.util.TreeMap<>(jsonPathContains).forEach((path, expected) -> {
                JsonNode value = JsonPath.read(root, path);
                if (value.isMissingNode() || !text(value).contains(expected)) {
                    failed.add(path + " contains");
                }
            });
        }
        return failed;
    }

    private JsonNode readJson(KafkaMessage message, ObjectMapper objectMapper) {
        try {
            return objectMapper.readTree(message.value());
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String text(JsonNode value) {
        return value.isValueNode() ? value.asText() : value.toString();
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
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
