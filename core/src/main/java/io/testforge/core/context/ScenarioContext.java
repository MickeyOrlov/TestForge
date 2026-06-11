package io.testforge.core.context;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Storage for values produced during a scenario (ids, responses, expected
 * values). Two carriers, one API:
 *
 * <ul>
 *   <li><b>Default:</b> thread-local — each test thread sees its own store;
 *       parallel execution is safe as long as one scenario stays on one
 *       thread. Pair with {@code ScenarioContextExtension} (or call
 *       {@link #clear()} in an after-hook) so reused worker threads start
 *       clean.</li>
 *   <li><b>Scoped:</b> inside {@link #runScoped(Runnable)} the store is
 *       carried by a {@link ScopedValue} binding — the block gets a fresh,
 *       isolated context and the surrounding thread-local store is untouched.
 *       Useful for nested preparation (a {@code PreparedDataProvider} driving
 *       its own flow must not pollute the calling test's context), and the
 *       forward-compatible path to structured concurrency: when
 *       {@code StructuredTaskScope} finalizes, forked virtual threads will
 *       inherit this binding automatically.</li>
 * </ul>
 */
public final class ScenarioContext {

    private static final ScopedValue<Map<ContextKey<?>, Object>> SCOPED_STORE =
            ScopedValue.newInstance();

    private static final ThreadLocal<Map<ContextKey<?>, Object>> THREAD_STORE =
            ThreadLocal.withInitial(HashMap::new);

    private ScenarioContext() {
    }

    /**
     * Runs the block with a fresh, isolated context store. The store is
     * concurrency-safe, so structured-concurrency children may write to it.
     */
    public static void runScoped(Runnable scenario) {
        ScopedValue.where(SCOPED_STORE, new ConcurrentHashMap<>()).run(scenario);
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
        if (SCOPED_STORE.isBound()) {
            SCOPED_STORE.get().clear();
        } else {
            THREAD_STORE.remove();
        }
    }

    private static Map<ContextKey<?>, Object> store() {
        return SCOPED_STORE.isBound() ? SCOPED_STORE.get() : THREAD_STORE.get();
    }

    private static String knownKeys() {
        return store().keySet().stream()
                .map(ContextKey::name)
                .sorted()
                .collect(Collectors.joining(", "));
    }
}
