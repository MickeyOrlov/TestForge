package io.testforge.core.diff;

import java.util.List;

/**
 * Result of comparing two states of a collection: what appeared, what
 * disappeared, and what stayed under the same key but changed content.
 */
public record StateDiff<T>(List<T> added, List<T> removed, List<Change<T>> changed) {

    public record Change<T>(T before, T after) {
    }

    public StateDiff {
        added = List.copyOf(added);
        removed = List.copyOf(removed);
        changed = List.copyOf(changed);
    }

    public boolean isEmpty() {
        return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
    }
}
