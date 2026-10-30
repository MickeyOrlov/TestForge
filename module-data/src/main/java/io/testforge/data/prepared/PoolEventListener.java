package io.testforge.data.prepared;

import java.util.List;

/**
 * Broadcast hooks for pool activity — the extension point for metrics,
 * logging or background refill, without coupling any of that to the pool
 * itself. All methods default to no-op; implement only what you need.
 */
public interface PoolEventListener {

    /** An object was handed to a test; {@code fromPool} is false on a cold miss. {@code tags} is sorted. */
    default void onAcquired(Class<?> type, List<String> tags, boolean fromPool) {
    }

    /** The provider built a new object (slow path or preload). */
    default void onPrepared(Class<?> type, List<String> tags) {
    }

    /**
     * A previously stocked variant ran dry — the signal to refill earlier.
     * Fired once per drain, on the first cold miss; a later {@code preload}
     * or refill re-arms it. {@code tags} is the variant's sorted tag set.
     */
    default void onExhausted(Class<?> type, List<String> tags) {
    }
}
