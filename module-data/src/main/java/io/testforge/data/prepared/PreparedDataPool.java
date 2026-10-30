package io.testforge.data.prepared;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory pool of prepared domain objects. Tests stop paying the
 * preparation cost per test: stock variants up front (a suite hook,
 * {@code forge.data.pool.preload}, or a background job calls {@link #preload}),
 * and {@link #acquire} falls back to on-the-spot preparation only on a cold miss.
 *
 * <p>Objects are handed out exactly once — there is deliberately no
 * release/return: a used domain object is dirty by definition.
 *
 * <p>A variant is a type plus a <em>set</em> of tags: {@code ["vip", "eu"]} and
 * {@code ["eu", "vip"]} are the same variant. Providers still receive the tags
 * in the order the caller gave them.
 *
 * <p>With {@link #refillWhenBelow} and {@link #enableRefill}, a variant whose
 * stock drops below its threshold is topped up on a background executor. A
 * refill failure is logged and counted, never thrown into a test: the next
 * acquire simply takes the cold path.
 */
public class PreparedDataPool {

    private static final Logger log = LoggerFactory.getLogger(PreparedDataPool.class);

    private final Map<Class<?>, PreparedDataProvider<?>> providers = new HashMap<>();
    private final ConcurrentMap<Variant, VariantState> variants = new ConcurrentHashMap<>();
    private final List<PoolEventListener> listeners;
    private volatile Executor refillExecutor;

    /** Order- and duplicate-insensitive identity of a variant. */
    private record Variant(Class<?> type, List<String> tags) {

        static Variant of(Class<?> type, List<String> tags) {
            return new Variant(type, List.copyOf(new TreeSet<>(tags)));
        }
    }

    private static final class VariantState {
        final Deque<Object> stock = new ConcurrentLinkedDeque<>();
        final AtomicBoolean everStocked = new AtomicBoolean();
        final AtomicBoolean exhaustionReported = new AtomicBoolean();
        final AtomicBoolean refilling = new AtomicBoolean();
        final AtomicLong hits = new AtomicLong();
        final AtomicLong coldMisses = new AtomicLong();
        final AtomicLong prepared = new AtomicLong();
        final AtomicLong exhausted = new AtomicLong();
        final AtomicLong refillFailures = new AtomicLong();
        volatile List<String> providerTags;
        volatile int refillBelow;
        volatile int refillTarget;
    }

    /**
     * One variant's counters at a point in time.
     *
     * @param hits       acquires served from stock
     * @param coldMisses acquires that had to prepare on the spot
     * @param prepared   objects the provider built (preload, refill and cold misses)
     * @param exhausted  times a stocked variant ran dry
     */
    public record VariantStats(String type, List<String> tags, int stocked, long hits, long coldMisses,
                               long prepared, long exhausted, long refillFailures) {
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
        Variant variant = Variant.of(type, tags);
        VariantState state = state(variant, tags);
        Object pooled = state.stock.pollFirst();
        if (pooled != null) {
            state.hits.incrementAndGet();
            notifyListeners(listener -> listener.onAcquired(type, variant.tags(), true));
            maybeRefill(variant, state);
            return type.cast(pooled);
        }
        // reported once per drain: every later miss is already visible as a cold acquire
        if (state.everStocked.get() && state.exhaustionReported.compareAndSet(false, true)) {
            state.exhausted.incrementAndGet();
            notifyListeners(listener -> listener.onExhausted(type, variant.tags()));
        }
        T prepared = prepare(type, tags, variant, state);
        state.coldMisses.incrementAndGet();
        notifyListeners(listener -> listener.onAcquired(type, variant.tags(), false));
        maybeRefill(variant, state);
        return prepared;
    }

    public <T> void preload(Class<T> type, List<String> tags, int count) {
        Variant variant = Variant.of(type, tags);
        VariantState state = state(variant, tags);
        for (int i = 0; i < count; i++) {
            state.stock.addLast(prepare(type, tags, variant, state));
        }
        if (count > 0) {
            state.everStocked.set(true);
            state.exhaustionReported.set(false);
        }
    }

    public int stocked(Class<?> type, List<String> tags) {
        VariantState state = variants.get(Variant.of(type, tags));
        return state == null ? 0 : state.stock.size();
    }

    /**
     * Keeps {@code type}/{@code tags} topped up to {@code target} whenever its
     * stock falls below {@code below}, once {@link #enableRefill} has supplied an
     * executor. {@code below <= 0} turns refill off for the variant.
     */
    public void refillWhenBelow(Class<?> type, List<String> tags, int below, int target) {
        if (below > target) {
            throw new IllegalArgumentException("refill threshold %d is above the target stock %d for %s %s"
                    .formatted(below, target, type.getSimpleName(), tags));
        }
        VariantState state = state(Variant.of(type, tags), tags);
        state.refillBelow = below;
        state.refillTarget = target;
    }

    public void enableRefill(Executor executor) {
        this.refillExecutor = Objects.requireNonNull(executor, "executor");
    }

    /** Counters for every variant the pool has seen, ordered by type then tags. */
    public List<VariantStats> stats() {
        List<VariantStats> stats = new ArrayList<>();
        variants.forEach((variant, state) -> stats.add(new VariantStats(
                variant.type().getName(), variant.tags(), state.stock.size(), state.hits.get(),
                state.coldMisses.get(), state.prepared.get(), state.exhausted.get(), state.refillFailures.get())));
        stats.sort(Comparator.comparing(VariantStats::type).thenComparing(s -> String.join(",", s.tags())));
        return stats;
    }

    private VariantState state(Variant variant, List<String> callerTags) {
        VariantState state = variants.computeIfAbsent(variant, ignored -> new VariantState());
        if (state.providerTags == null) {
            state.providerTags = List.copyOf(callerTags);
        }
        return state;
    }

    private void maybeRefill(Variant variant, VariantState state) {
        Executor executor = refillExecutor;
        if (executor == null || state.refillBelow <= 0 || state.stock.size() >= state.refillBelow) {
            return;
        }
        if (!state.refilling.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> refill(variant, state));
        } catch (RejectedExecutionException e) {
            // shutting down: nothing to refill for
            state.refilling.set(false);
        }
    }

    private void refill(Variant variant, VariantState state) {
        try {
            while (state.stock.size() < state.refillTarget) {
                state.stock.addLast(prepare(variant.type(), state.providerTags, variant, state));
                state.everStocked.set(true);
                state.exhaustionReported.set(false);
            }
        } catch (RuntimeException e) {
            state.refillFailures.incrementAndGet();
            log.warn("Background refill of {} {} failed; tests fall back to preparing on the spot: {}",
                    variant.type().getSimpleName(), variant.tags(), e.getMessage());
        } finally {
            state.refilling.set(false);
        }
    }

    private <T> T prepare(Class<T> type, List<String> tags, Variant variant, VariantState state) {
        @SuppressWarnings("unchecked")
        PreparedDataProvider<T> provider = (PreparedDataProvider<T>) providers.get(type);
        if (provider == null) {
            throw new IllegalStateException(
                    "No PreparedDataProvider for %s. Register a bean implementing PreparedDataProvider<%s> — see module-data README."
                            .formatted(type.getName(), type.getSimpleName()));
        }
        T prepared = provider.prepare(tags);
        state.prepared.incrementAndGet();
        notifyListeners(listener -> listener.onPrepared(type, variant.tags()));
        return prepared;
    }

    private void notifyListeners(Consumer<PoolEventListener> event) {
        listeners.forEach(event);
    }
}
