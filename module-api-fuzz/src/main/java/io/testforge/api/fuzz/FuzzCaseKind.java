package io.testforge.api.fuzz;

/**
 * What a case does to a request.
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
 *
 * <p>Each kind also carries its {@link FuzzCaseCategory}, so a reader — and the
 * coverage report — can tell a broken value from a broken envelope without
 * consulting a list kept somewhere else.
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
    /** The same element twice, against a declared {@code uniqueItems}. */
    DUPLICATE_ITEM,

    // --- objects ---

    /** A property the schema never declared, against {@code additionalProperties}. */
    UNDECLARED_PROPERTY,
    /** A {@code readOnly} property sent in a request, where it does not belong. */
    READ_ONLY_IN_REQUEST,

    // --- robustness probes ---

    /** Structural characters, to probe escaping and encoding. */
    ENCODING_PROBE,
    /** Multi-byte characters, for services that assume one byte per character. */
    UNICODE,

    // --- protocol ---

    /** Syntactically broken JSON where the document declares a JSON body. */
    MALFORMED_JSON(FuzzCaseCategory.PROTOCOL_MUTATION),
    /** A media type the operation does not declare. */
    UNSUPPORTED_CONTENT_TYPE(FuzzCaseCategory.PROTOCOL_MUTATION),
    /** A body with no {@code Content-Type} header at all. */
    MISSING_CONTENT_TYPE(FuzzCaseCategory.PROTOCOL_MUTATION),
    /** No body where the document marks the request body required. */
    EMPTY_BODY(FuzzCaseCategory.PROTOCOL_MUTATION);

    private final FuzzCaseCategory category;

    FuzzCaseKind() {
        this(FuzzCaseCategory.SCHEMA_MUTATION);
    }

    FuzzCaseKind(FuzzCaseCategory category) {
        this.category = category;
    }

    public FuzzCaseCategory category() {
        return category;
    }
}
