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
        if (topic != null && !topic.equals(message.topic())) {
            return false;
        }
        if (key != null && !normalize(key).equals(normalize(message.key()))) {
            return false;
        }
        for (String fragment : valueContains) {
            if (!message.value().contains(fragment)) {
                return false;
            }
        }
        for (Map.Entry<String, String> expectedHeader : headerEquals.entrySet()) {
            if (!expectedHeader.getValue().equals(message.headers().get(expectedHeader.getKey()))) {
                return false;
            }
        }
        if (!jsonPathEquals.isEmpty() || !jsonPathContains.isEmpty()) {
            JsonNode root = readJson(message, objectMapper);
            if (root == null) {
                return false;
            }
            if (!matchesJsonEquals(root)) {
                return false;
            }
            return matchesJsonContains(root);
        }
        return true;
    }

    private boolean matchesJsonEquals(JsonNode root) {
        for (Map.Entry<String, String> expectedValue : jsonPathEquals.entrySet()) {
            JsonNode value = JsonPath.read(root, expectedValue.getKey());
            if (value.isMissingNode() || !expectedValue.getValue().equals(text(value))) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesJsonContains(JsonNode root) {
        for (Map.Entry<String, String> expectedValue : jsonPathContains.entrySet()) {
            JsonNode value = JsonPath.read(root, expectedValue.getKey());
            if (value.isMissingNode() || !text(value).contains(expectedValue.getValue())) {
                return false;
            }
        }
        return true;
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
