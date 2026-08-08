package io.testforge.api.fuzz;

/**
 * What repeating a finding proved.
 *
 * <p>Bounded on purpose: a handful of attempts answers "is this real" without
 * turning into a statistical engine, and the count is reported so a reader can
 * judge the evidence rather than trust a label.
 */
public record ConfirmationResult(
        Reproducibility reproducibility,
        int attempts,
        int matches,
        String reason) {

    public static ConfirmationResult notConfirmed() {
        return new ConfirmationResult(Reproducibility.NOT_CONFIRMED, 0, 0, null);
    }

    /** Confirmation was deliberately not attempted, with the reason to report. */
    public static ConfirmationResult notAttempted(String reason) {
        return new ConfirmationResult(Reproducibility.NOT_ATTEMPTED, 0, 0, reason);
    }

    public static ConfirmationResult of(int attempts, int matches, String reason) {
        ConfirmationResult result = of(attempts, matches);
        return new ConfirmationResult(result.reproducibility(), attempts, matches, reason);
    }

    public static ConfirmationResult of(int attempts, int matches) {
        Reproducibility reproducibility;
        if (matches == 0) {
            reproducibility = Reproducibility.DISAPPEARED;
        } else if (matches == attempts) {
            reproducibility = Reproducibility.REPRODUCIBLE;
        } else {
            reproducibility = Reproducibility.FLAKY;
        }
        return new ConfirmationResult(reproducibility, attempts, matches, null);
    }

    /** {@code REPRODUCIBLE (2/2)} — the form the report and the artifact both use. */
    public String summary() {
        return attempts == 0
                ? reproducibility.toString()
                : "%s (%d/%d)".formatted(reproducibility, matches, attempts);
    }

    /**
     * Whether the finding still looks real enough to spend shrink attempts on.
     * A disappeared finding is not worth minimizing; an unconfirmed one is,
     * because the original observation is still the best evidence available.
     */
    public boolean worthMinimizing() {
        return reproducibility != Reproducibility.DISAPPEARED;
    }
}
