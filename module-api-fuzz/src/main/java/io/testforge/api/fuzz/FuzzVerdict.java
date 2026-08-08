package io.testforge.api.fuzz;

/**
 * The conclusion about <em>validation behaviour</em>, and nothing else.
 *
 * <p>v1.1 folded independent facts into this enum: a {@code 500} and an
 * undocumented response and an echoed value all competed for the same slot, so
 * whichever ranked highest erased the others. Those are now
 * {@link FuzzEvidence} — facts about the response, recorded alongside whatever
 * this verdict concludes.
 *
 * <p>What remains here is only what the run can defend about validation, and
 * it can defend nothing at all unless the control request was accepted. That
 * is what {@link #INCONCLUSIVE} is for, and it is the honest answer far more
 * often than a fuzzer's output usually admits.
 */
public enum FuzzVerdict {

    /** The service treated the value the way the document implies it should. */
    PASSED,

    /** A value the document forbids was accepted. */
    OVER_PERMISSIVE,

    /** A value the document permits was refused. */
    OVER_STRICT,

    /**
     * Nothing about validation can be concluded — the control request was not
     * accepted, or the mutated request was answered by infrastructure rather
     * than by the handler.
     */
    INCONCLUSIVE,

    /** The case did not apply to this operation's baseline. */
    NOT_APPLICABLE;

    /** Only these two are claims that the service got validation wrong. */
    public boolean finding() {
        return this == OVER_PERMISSIVE || this == OVER_STRICT;
    }
}
