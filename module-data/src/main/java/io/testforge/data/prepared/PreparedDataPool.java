package io.testforge.data.prepared;

import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory pool of prepared domain objects. Tests stop paying the
 * preparation cost per test: stock variants up front (a suite hook or a
 * background job calls {@link #preload}), and {@link #acquire} falls back to
 * on-the-spot preparation only on a cold miss.
 *
 * <p>Objects are handed out exactly once — there is deliberately no
 * release/return: a used domain object is dirty by definition.
 */
public class PreparedDataPool {

    private final Map<Class<?>, PreparedDataProvider<?>> providers = new HashMap<>();
    private final ConcurrentMap<Variant, Deque<Object>> stock = new ConcurrentHashMap<>();
    private final List<PoolEventListener> listeners;

    private record Variant(Class<?> type, List<String> tags) {
    }

    public PreparedDataPool(List<PreparedDataProvider<?>> providers, List<PoolEventListener> listeners) {
        for (PreparedDataProvider<?> provider : providers) {
            PreparedDataProvider<?> previous = this.providers.put(provider.type(), provider);
            if (previous != null) {
                throw new IllegalStateException(
                        "Two PreparedDataProviders registered for type " + provider.type().getName());
            }
        }
        this.listeners = List.copyOf(listeners);
    }

    public <T> T acquire(Class<T> type, List<String> tags) {
        Variant variant = new Variant(type, List.copyOf(tags));

        Deque<Object> available = stock.get(variant);
        Object pooled = available == null ? null : available.pollFirst();
        if (pooled != null) {
            notifyListeners(listener -> listener.onAcquired(type, variant.tags(), true));
            return type.cast(pooled);
        }
        if (available != null) {
            notifyListeners(listener -> listener.onExhausted(type, variant.tags()));
        }

        T prepared = prepare(type, variant.tags());
        notifyListeners(listener -> listener.onAcquired(type, variant.tags(), false));
        return prepared;
    }

    public <T> void preload(Class<T> type, List<String> tags, int count) {
        Variant variant = new Variant(type, List.copyOf(tags));
        Deque<Object> deque = stock.computeIfAbsent(variant, ignored -> new ConcurrentLinkedDeque<>());
        for (int i = 0; i < count; i++) {
            deque.addLast(prepare(type, variant.tags()));
        }
    }

    public int stocked(Class<?> type, List<String> tags) {
        Deque<Object> deque = stock.get(new Variant(type, List.copyOf(tags)));
        return deque == null ? 0 : deque.size();
    }

    private <T> T prepare(Class<T> type, List<String> tags) {
        @SuppressWarnings("unchecked")
        PreparedDataProvider<T> provider = (PreparedDataProvider<T>) providers.get(type);
        if (provider == null) {
            throw new IllegalStateException(
                    "No PreparedDataProvider for %s. Register a bean implementing PreparedDataProvider<%s> — see module-data README."
                            .formatted(type.getName(), type.getSimpleName()));
        }
        T prepared = provider.prepare(tags);
        notifyListeners(listener -> listener.onPrepared(type, tags));
        return prepared;
    }

    private void notifyListeners(java.util.function.Consumer<PoolEventListener> event) {
        listeners.forEach(event);
    }
}
