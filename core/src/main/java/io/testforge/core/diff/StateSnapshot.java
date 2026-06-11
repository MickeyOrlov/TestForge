package io.testforge.core.diff;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Snapshot of a collection keyed by an extracted id, for side-effect
 * assertions stronger than "the row exists": after an action, assert that
 * exactly N entries appeared, none disappeared and none were touched.
 *
 * <pre>{@code
 * var before = StateSnapshot.of(repository.findAll(), TaskRecord::getId);
 * // ... the action under test ...
 * StateDiff<TaskRecord> diff = before.diff(repository.findAll(),
 *         (a, b) -> Objects.equals(a.getStatus(), b.getStatus()));
 * assertThat(diff.added()).hasSize(1);
 * assertThat(diff.removed()).isEmpty();
 * assertThat(diff.changed()).isEmpty();
 * }</pre>
 *
 * <p>This catches the bug classes that "find the row" assertions silently
 * miss: duplicate inserts from retries, accidental updates of neighbouring
 * rows, cascade deletes.
 */
public final class StateSnapshot<T, K> {

    private final Map<K, T> byKey;
    private final Function<? super T, ? extends K> keyExtractor;

    private StateSnapshot(Map<K, T> byKey, Function<? super T, ? extends K> keyExtractor) {
        this.byKey = byKey;
        this.keyExtractor = keyExtractor;
    }

    public static <T, K> StateSnapshot<T, K> of(
            Collection<? extends T> items,
            Function<? super T, ? extends K> keyExtractor) {
        return new StateSnapshot<>(index(items, keyExtractor), keyExtractor);
    }

    /** Diff using {@link Object#equals} to decide whether an entry changed. */
    public StateDiff<T> diff(Collection<? extends T> current) {
        return diff(current, Objects::equals);
    }

    /**
     * Diff with an explicit sameness predicate — useful for JPA entities
     * without a content-based equals.
     */
    public StateDiff<T> diff(Collection<? extends T> current, BiPredicate<? super T, ? super T> same) {
        Map<K, T> currentByKey = index(current, keyExtractor);

        List<T> added = new ArrayList<>();
        List<T> removed = new ArrayList<>();
        List<StateDiff.Change<T>> changed = new ArrayList<>();

        currentByKey.forEach((key, now) -> {
            T before = byKey.get(key);
            if (before == null) {
                added.add(now);
            } else if (!same.test(before, now)) {
                changed.add(new StateDiff.Change<>(before, now));
            }
        });

        byKey.forEach((key, before) -> {
            if (!currentByKey.containsKey(key)) {
                removed.add(before);
            }
        });

        return new StateDiff<>(added, removed, changed);
    }

    public int size() {
        return byKey.size();
    }

    private static <T, K> Map<K, T> index(
            Collection<? extends T> items,
            Function<? super T, ? extends K> keyExtractor) {
        Map<K, T> byKey = new LinkedHashMap<>();
        for (T item : items) {
            K key = keyExtractor.apply(item);
            T previous = byKey.put(key, item);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate key in snapshot: '%s'. Use a unique key extractor.".formatted(key));
            }
        }
        return byKey;
    }
}
