package io.testforge.core.context;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Thread-local storage for values produced during a scenario (ids, responses,
 * expected values). Each test thread sees its own isolated store, so parallel
 * execution is safe as long as one scenario stays on one thread.
 *
 * <p>Call {@link #clear()} in an after-hook to avoid leaking state between
 * scenarios that reuse the same worker thread.
 */
public final class ScenarioContext {

    private static final ThreadLocal<Map<ContextKey<?>, Object>> STORE =
            ThreadLocal.withInitial(HashMap::new);

    private ScenarioContext() {
    }

    public static <T> void put(ContextKey<T> key, T value) {
        STORE.get().put(key, value);
    }

    public static <T> T get(ContextKey<T> key) {
        return find(key).orElseThrow(() -> new IllegalStateException(
                "No value for key '%s' in scenario context. Known keys: [%s]"
                        .formatted(key.name(), knownKeys())));
    }

    public static <T> Optional<T> find(ContextKey<T> key) {
        return Optional.ofNullable(key.type().cast(STORE.get().get(key)));
    }

    public static void clear() {
        STORE.remove();
    }

    private static String knownKeys() {
        return STORE.get().keySet().stream()
                .map(ContextKey::name)
                .sorted()
                .collect(Collectors.joining(", "));
    }
}
