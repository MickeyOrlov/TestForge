package io.testforge.api.fuzz;

import java.util.List;

/**
 * What minimization achieved, and what it cost.
 *
 * <p>Sizes are reported in fields rather than bytes because that is what a
 * reader has to hold in their head: "18 fields down to 3" says the reproducer
 * is readable, where a byte count says nothing.
 */
public record ShrinkOutcome(
        boolean attempted,
        int attempts,
        Integer originalSize,
        Integer minimalSize,
        String minimalBody,
        List<String> removed,
        String reason) {

    public ShrinkOutcome {
        removed = List.copyOf(removed == null ? List.of() : removed);
    }

    public static ShrinkOutcome notAttempted() {
        return new ShrinkOutcome(false, 0, null, null, null, List.of(), null);
    }

    public static ShrinkOutcome refused(String reason) {
        return new ShrinkOutcome(false, 0, null, null, null, List.of(), reason);
    }

    public static ShrinkOutcome of(int attempts, int originalSize, int minimalSize,
                                   String minimalBody, List<String> removed) {
        return new ShrinkOutcome(true, attempts, originalSize, minimalSize, minimalBody, removed, null);
    }

    /** True when the request actually got smaller. */
    public boolean reduced() {
        return attempted && originalSize != null && minimalSize != null && minimalSize < originalSize;
    }

    public String summary() {
        if (!attempted) {
            return reason == null ? "not attempted" : reason;
        }
        return reduced()
                ? "%d → %d fields in %d attempts".formatted(originalSize, minimalSize, attempts)
                : "already minimal (%d attempts)".formatted(attempts);
    }
}
