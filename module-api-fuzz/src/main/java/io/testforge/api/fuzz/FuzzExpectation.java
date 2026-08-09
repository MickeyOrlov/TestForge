package io.testforge.api.fuzz;

/**
 * What the document implies should happen to a case's value.
 *
 * <p>This is the whole reason schema-aware fuzzing beats random input: a value
 * that violates a declared constraint <em>should</em> be rejected, so a
 * {@code 200} is a finding rather than a success. Without the schema there is
 * nothing to compare the answer against, and every response looks equally fine.
 */
public enum FuzzExpectation {

    /** The value breaks a declared constraint; a well-behaved service refuses it. */
    REJECT,

    /** The value is valid, if extreme; refusing it is over-strict validation. */
    ACCEPT,

    /**
     * The document says nothing either way — an empty string, an unusual
     * encoding. Only a crash or a reflection is a finding here.
     */
    UNSPECIFIED
}
