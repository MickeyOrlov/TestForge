package io.testforge.api.explorer;

/**
 * What happened to one operation.
 *
 * <p>Four states, deliberately. The tempting fifth ones — "returned 4xx",
 * "returned 5xx" — are not outcomes of the exploration, they are properties of
 * the response, and whether they matter is exactly what the document decides.
 * A documented {@code 404} is {@link #PASSED}; an undocumented {@code 500} is
 * {@link #CONTRACT_MISMATCH}. Folding the status into the outcome would hide
 * that distinction, which is the one the report exists to make.
 */
public enum ExplorerOutcome {

    /** The call went out and the response matched what the document promises. */
    PASSED,

    /** The call went out; the response deviates from the document. */
    CONTRACT_MISMATCH,

    /** The call could not be completed — connection refused, timeout, unreadable response. */
    FAILED,

    /** The call was never made: policy, or a required value nobody could supply. */
    SKIPPED
}
