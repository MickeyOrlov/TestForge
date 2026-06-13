package io.testforge.core.context;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Storage for values produced during a scenario (ids, responses, expected
 * values). Two usage modes, one API:
 *
 * <ul>
 *   <li><b>Default:</b> thread-local — each test thread sees its own store;
 *       parallel execution is safe as long as one scenario stays on one
 *       thread. Pair with {@code ScenarioContextExtension} (or call
 *       {@link #clear()} in an after-hook) so reused worker threads start
 *       clean.</li>
 *   <li><b>Scoped:</b> inside {@link #runScoped(Runnable)} the store is
 *       replaced for the duration of the block — nested preparation gets a
 *       fresh, isolated context and the caller's store is restored afterwards.
 *       Useful when a {@code PreparedDataProvider} drives its own flow and
 *       must not pollute the calling test's context.</li>
 * </ul>
 */
public final class ScenarioContext {

    private static final ThreadLocal<Map<ContextKey<?>, Object>> THREAD_STORE = new ThreadLocal<>();

    private ScenarioContext() {
    }

    /**
     * Runs the block with a fresh, isolated context store. The store is
     * restored to the caller's previous state even when the block fails.
     */
    public static void runScoped(Runnable scenario) {
        Objects.requireNonNull(scenario, "scenario");
        Map<ContextKey<?>, Object> previous = THREAD_STORE.get();
        THREAD_STORE.set(new ConcurrentHashMap<>());
        try {
            scenario.run();
        } finally {
            if (previous == null) {
                THREAD_STORE.remove();
            } else {
                THREAD_STORE.set(previous);
            }
        }
    }

    public static <T> void put(ContextKey<T> key, T value) {
        Objects.requireNonNull(value, () -> "Scenario context value for '%s' must not be null; use remove semantics via clear() or a dedicated absent state".formatted(key.name()));
        store().put(key, value);
    }

    public static <T> T get(ContextKey<T> key) {
        return find(key).orElseThrow(() -> new IllegalStateException(
                "No value for key '%s' in scenario context. Known keys: [%s]"
                        .formatted(key.name(), knownKeys())));
    }

    public static <T> Optional<T> find(ContextKey<T> key) {
        return Optional.ofNullable(key.type().cast(store().get(key)));
    }

    public static void clear() {
        THREAD_STORE.remove();
    }

    private static Map<ContextKey<?>, Object> store() {
        Map<ContextKey<?>, Object> store = THREAD_STORE.get();
        if (store == null) {
            store = new HashMap<>();
            THREAD_STORE.set(store);
        }
        return store;
    }

    private static String knownKeys() {
        return store().keySet().stream()
                .map(ContextKey::name)
                .sorted()
                .collect(Collectors.joining(", "));
    }
}
