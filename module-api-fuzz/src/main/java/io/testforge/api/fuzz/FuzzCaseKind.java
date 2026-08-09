package io.testforge.api.fuzz;

/**
 * The kinds of value this module derives from a parameter's schema.
 *
 * <p>All of them are <em>schema-aware</em>: each one exists because the
 * document said something specific about the parameter, which is what lets the
 * run judge the answer. A generic fuzzer can tell you the service returned 500;
 * only a schema-aware one can tell you the service accepted a value its own
 * document forbids.
 */
public enum FuzzCaseKind {

    /** Empty string where the document declares one. */
    EMPTY_STRING(FuzzExpectation.UNSPECIFIED),

    /** Longer than {@code maxLength}, or long enough to find a column limit. */
    TOO_LONG(FuzzExpectation.REJECT),

    /** Shorter than {@code minLength}. */
    TOO_SHORT(FuzzExpectation.REJECT),

    /** Exactly {@code minLength} or {@code minimum} — the valid edge. */
    AT_LOWER_BOUND(FuzzExpectation.ACCEPT),

    /** Exactly {@code maxLength} or {@code maximum} — the other valid edge. */
    AT_UPPER_BOUND(FuzzExpectation.ACCEPT),

    /** Below {@code minimum}. */
    BELOW_MINIMUM(FuzzExpectation.REJECT),

    /** Above {@code maximum}. */
    ABOVE_MAXIMUM(FuzzExpectation.REJECT),

    /** Text where a number is declared, a number where a boolean is. */
    WRONG_TYPE(FuzzExpectation.REJECT),

    /** A fraction where {@code integer} is declared. */
    FRACTIONAL_FOR_INTEGER(FuzzExpectation.REJECT),

    /** Negative where the schema implies a count or identifier. */
    NEGATIVE(FuzzExpectation.UNSPECIFIED),

    /** Malformed against the declared {@code format} — not a UUID, not a date. */
    FORMAT_VIOLATION(FuzzExpectation.REJECT),

    /** Fails the declared {@code pattern}. */
    PATTERN_VIOLATION(FuzzExpectation.REJECT),

    /** A value the declared {@code enum} does not contain. */
    ENUM_OUTSIDER(FuzzExpectation.REJECT),

    /** A required parameter left out entirely. */
    OMITTED_REQUIRED(FuzzExpectation.REJECT),

    /**
     * Structural characters — quote, angle bracket, ampersand, newline. These
     * probe escaping and encoding, not vulnerabilities: what matters is whether
     * the value comes back unescaped or blows the service up, and both are
     * contract defects a test suite should own.
     */
    ENCODING_PROBE(FuzzExpectation.UNSPECIFIED),

    /** Multi-byte characters, for services that still assume one byte per char. */
    UNICODE(FuzzExpectation.UNSPECIFIED);

    private final FuzzExpectation expectation;

    FuzzCaseKind(FuzzExpectation expectation) {
        this.expectation = expectation;
    }

    /** What the document implies the service should do with this value. */
    public FuzzExpectation expectation() {
        return expectation;
    }
}
