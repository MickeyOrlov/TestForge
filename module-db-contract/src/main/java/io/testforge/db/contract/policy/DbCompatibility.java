package io.testforge.db.contract.policy;

/**
 * Verdict a {@link DbCompatibilityPolicy} gives one schema change.
 *
 * <p>{@link #NON_BREAKING}, {@link #RISKY} and {@link #BREAKING} form the
 * severity axis, in that order. {@link #UNKNOWN} is <em>not</em> a point on that
 * axis: it means the change was not classified at all, so it is neither more nor
 * less severe than a risky change. Ranking it against the others would invent an
 * answer the policy explicitly declined to give.
 *
 * <p>The consequence is that {@code UNKNOWN} gets its own gate
 * ({@code forge.db-contract.fail-on.unknown}), independent of the one for risky
 * changes, and that it never contributes to
 * {@link io.testforge.db.contract.DbContractReport#worstClassified()}.
 */
public enum DbCompatibility {

    /** The change cannot break a consumer of this schema. */
    NON_BREAKING,

    /** The change can break a consumer depending on data or on how it reads and writes. */
    RISKY,

    /** The change breaks consumers of this schema. */
    BREAKING,

    /** TestForge did not classify this change; it carries no severity. */
    UNKNOWN;

    /**
     * Whether this verdict sits on the severity axis at all.
     *
     * @return {@code false} only for {@link #UNKNOWN}
     */
    public boolean classified() {
        return this != UNKNOWN;
    }
}
