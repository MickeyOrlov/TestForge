package io.testforge.api.fuzz;

/**
 * Which layer of the request a case attacks.
 *
 * <p>The two are counted apart because they answer different questions and
 * mixing them makes both numbers lie. A schema mutation asks whether the service
 * enforces a rule its own document declares, so it maps onto a declared
 * constraint and belongs in constraint coverage. A protocol mutation asks
 * whether the service survives a request that is malformed before any schema is
 * reached — broken JSON, a missing {@code Content-Type} — and maps onto no
 * constraint at all.
 *
 * <p>Folding protocol cases into coverage would inflate it with work that
 * exercised nothing the document promised; leaving them out of the report
 * entirely would hide the crashes they find. So they are reported, separately.
 */
public enum FuzzCaseCategory {

    /** Violates a rule the document declares about a value. */
    SCHEMA_MUTATION,

    /** Breaks the request envelope before any schema applies. */
    PROTOCOL_MUTATION
}
