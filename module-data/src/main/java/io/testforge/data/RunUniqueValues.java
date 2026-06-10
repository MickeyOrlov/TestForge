package io.testforge.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

public class RunUniqueValues {

    private final ConcurrentMap<String, Set<String>> valuesByType = new ConcurrentHashMap<>();

    public boolean tryRegister(String type, String value) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }

        Set<String> values = valuesByType.computeIfAbsent(
                type,
                ignored -> ConcurrentHashMap.newKeySet());
        return values.add(value);
    }

    public String generate(String type, Supplier<String> generator, int maxAttempts) {
        if (generator == null) {
            throw new IllegalArgumentException("generator must not be null");
        }
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String value = generator.get();
            if (tryRegister(type, value)) {
                return value;
            }
        }

        throw new IllegalStateException(
                "Could not generate unique value for type '%s' after %s attempts"
                        .formatted(type, maxAttempts));
    }

    public void clear() {
        valuesByType.clear();
    }

    public void clear(String type) {
        valuesByType.remove(type);
    }

    public Map<String, Set<String>> snapshot() {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        valuesByType.forEach((type, values) -> copy.put(type, Set.copyOf(values)));
        return Collections.unmodifiableMap(copy);
    }
}
