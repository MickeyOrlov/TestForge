package io.testforge.core.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.core.json.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a message must look like: channel, key, fragments of its value,
 * headers, JSON paths. A {@code null} or empty criterion matches anything.
 *
 * @param channelName how the broker calls the channel, for {@link #mismatches}: {@code topic}, {@code exchange}
 * @param keyName     how the broker calls the key: {@code key}, {@code routing key}
 */
public record MessageCriteria(
        String channel,
        String key,
        List<String> valueContains,
        Map<String, String> headerEquals,
        Map<String, String> jsonPathEquals,
        Map<String, String> jsonPathContains,
        String channelName,
        String keyName) {

    public MessageCriteria {
        valueContains = List.copyOf(valueContains == null ? List.of() : valueContains);
        headerEquals = Map.copyOf(headerEquals == null ? Map.of() : headerEquals);
        jsonPathEquals = Map.copyOf(jsonPathEquals == null ? Map.of() : jsonPathEquals);
        jsonPathContains = Map.copyOf(jsonPathContains == null ? Map.of() : jsonPathContains);
        channelName = channelName == null ? "channel" : channelName;
        keyName = keyName == null ? "key" : keyName;
    }

    public boolean matches(BrokerMessage message, ObjectMapper objectMapper) {
        return mismatches(message, objectMapper).isEmpty();
    }

    /**
     * The criteria {@code message} fails, by name only — {@code key},
     * {@code header X-Scope}, {@code $.payload.status} — never the values, so a
     * timeout can say how close a message came without printing its payload.
     */
    public List<String> mismatches(BrokerMessage message, ObjectMapper objectMapper) {
        List<String> failed = new ArrayList<>();
        if (channel != null && !channel.equals(message.channel())) {
            failed.add(channelName);
            return failed;
        }
        if (key != null && !normalize(key).equals(normalize(message.key()))) {
            failed.add(keyName);
        }
        for (int i = 0; i < valueContains.size(); i++) {
            if (!message.value().contains(valueContains.get(i))) {
                failed.add("value contains #" + (i + 1));
            }
        }
        for (Map.Entry<String, String> expected : new TreeMap<>(headerEquals).entrySet()) {
            if (!expected.getValue().equals(message.headers().get(expected.getKey()))) {
                failed.add("header " + expected.getKey());
            }
        }
        if (!jsonPathEquals.isEmpty() || !jsonPathContains.isEmpty()) {
            JsonNode root = readJson(message, objectMapper);
            if (root == null) {
                failed.add("value is not JSON");
                return failed;
            }
            new TreeMap<>(jsonPathEquals).forEach((path, expected) -> {
                JsonNode value = JsonPath.read(root, path);
                if (value.isMissingNode() || !expected.equals(text(value))) {
                    failed.add(path);
                }
            });
            new TreeMap<>(jsonPathContains).forEach((path, expected) -> {
                JsonNode value = JsonPath.read(root, path);
                if (value.isMissingNode() || !text(value).contains(expected)) {
                    failed.add(path + " contains");
                }
            });
        }
        return failed;
    }

    private static JsonNode readJson(BrokerMessage message, ObjectMapper objectMapper) {
        try {
            return objectMapper.readTree(message.value());
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String text(JsonNode value) {
        return value.isValueNode() ? value.asText() : value.toString();
    }

    /** A key serialized as a JSON string ({@code "42"}) matches {@code 42}. */
    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}
