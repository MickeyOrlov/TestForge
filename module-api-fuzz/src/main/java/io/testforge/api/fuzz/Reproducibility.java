package io.testforge.api.fuzz;

/**
 * How much the run actually knows about whether a finding is real.
 *
 * <p>The distinction that matters is between {@link #NOT_CONFIRMED} and
 * {@link #REPRODUCIBLE}. A fuzzer that reports every one-off response as a
 * defect wastes an engineer's afternoon; one that quietly drops the
 * intermittent ones hides the worst bugs there are. Naming the difference
 * costs a handful of requests and settles the argument.
 */
public enum Reproducibility {

    /** Confirmation was not run — the default, and the only free option. */
    NOT_CONFIRMED,

    /** Every confirmation attempt showed the same finding. */
    REPRODUCIBLE,

    /** Some attempts showed it and some did not. Still reported, and labelled. */
    FLAKY,

    /** No confirmation attempt showed it again. */
    DISAPPEARED,

    /** Confirmation was refused — an unsafe method without the explicit opt-in. */
    NOT_ATTEMPTED
}
