package io.testforge.api.fuzz;

/**
 * What a case does to a value.
 *
 * <p>The kind says <em>what was changed</em>, never what the service ought to
 * do about it. That judgement is {@link FuzzExpectation}, and it belongs to the
 * individual case because the same mutation means different things in different
 * documents: a 4096-character string is schema-proven invalid against
 * {@code maxLength: 8} and merely unusual against a schema that declares no
 * length at all.
 *
 * <p>v1 attached the expectation to the kind, which quietly produced false
 * findings — a long string was reported as {@code OVER_PERMISSIVE} even when
 * nothing in the document forbade it. Keeping the two apart is what stops that
 * from coming back.
 */
public enum FuzzCaseKind {

    // --- string ---

    EMPTY_STRING,
    /** Longer than {@code maxLength}, or simply long when none is declared. */
    TOO_LONG,
    TOO_SHORT,
    /** Fails a declared {@code pattern} — only generated when that is verifiable. */
    PATTERN_VIOLATION,
    /** Malformed against a {@code format} whose rules this module knows. */
    FORMAT_VIOLATION,

    // --- numeric ---

    BELOW_MINIMUM,
    ABOVE_MAXIMUM,
    /** The excluded bound itself, where {@code exclusiveMinimum}/{@code Maximum} applies. */
    AT_EXCLUSIVE_BOUND,
    /** Not a multiple of the declared {@code multipleOf}. */
    NOT_MULTIPLE_OF,
    FRACTIONAL_FOR_INTEGER,
    /** Negative where no {@code minimum} is declared — a probe, not a violation. */
    NEGATIVE,
    /** Far past any plausible range where no {@code maximum} is declared. */
    HUGE_NUMBER,

    // --- shared ---

    /** The valid edge: exactly {@code minLength}, {@code minimum}, {@code minItems}. */
    AT_LOWER_BOUND,
    /** The other valid edge. */
    AT_UPPER_BOUND,
    /** A type the schema does not declare. */
    WRONG_TYPE,
    /** A value outside a declared {@code enum}. */
    ENUM_OUTSIDER,
    /** A required field or parameter left out. */
    OMITTED_REQUIRED,
    /** {@code null} where the schema does not permit it. */
    NULL_FOR_NON_NULLABLE,

    // --- arrays ---

    EMPTY_ARRAY,
    TOO_FEW_ITEMS,
    TOO_MANY_ITEMS,
    /** An element whose type the item schema does not declare. */
    INVALID_ITEM_TYPE,

    // --- robustness probes ---

    /** Structural characters, to probe escaping and encoding. */
    ENCODING_PROBE,
    /** Multi-byte characters, for services that assume one byte per character. */
    UNICODE
}
