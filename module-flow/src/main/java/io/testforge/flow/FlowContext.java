package io.testforge.flow;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class FlowContext {

    private final Map<String, Object> values = new HashMap<>();

    /**
     * Stores {@code value}; {@code null} removes the key — {@link #get} already
     * treats a null as absent, and a null kept here would break the snapshot a
     * successful run returns.
     */
    public <T> void put(String key, T value) {
        if (value == null) {
            values.remove(key);
        } else {
            values.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Object value = values.get(key);
        if (value == null) {
            throw new FlowException("Flow context key is absent: " + key);
        }
        if (!type.isInstance(value)) {
            throw new FlowException("Flow context key '%s' expected %s but got %s"
                    .formatted(key, type.getSimpleName(), value.getClass().getSimpleName()));
        }
        return (T) value;
    }

    public <T> Optional<T> getOptional(String key, Class<T> type) {
        Object value = values.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (!type.isInstance(value)) {
            throw new FlowException("Flow context key '%s' expected %s but got %s"
                    .formatted(key, type.getSimpleName(), value.getClass().getSimpleName()));
        }
        return Optional.of(type.cast(value));
    }

    public Map<String, Object> snapshot() {
        return Map.copyOf(values);
    }
}
