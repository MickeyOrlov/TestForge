package io.testforge.api.explorer;

/**
 * The runtime contract checks of v1. Each one answers a question a team asks on
 * its first week with an unfamiliar API: does the document describe what the
 * service actually does?
 */
public enum MismatchKind {

    /** The service answered with a status the document never mentions. */
    UNDOCUMENTED_STATUS,

    /** The response media type is not among the ones declared for that status. */
    UNEXPECTED_CONTENT_TYPE,

    /** A property the schema marks as required is absent from the response. */
    MISSING_REQUIRED_FIELD,

    /**
     * The response carries a property the schema does not declare. Reported
     * because real documents almost never set {@code additionalProperties:
     * false}, so schema validation alone stays silent about it.
     */
    UNDOCUMENTED_FIELD,

    /** A property is present but its JSON type cannot satisfy the declared one. */
    INCOMPATIBLE_FIELD_TYPE,

    /** The declared media type is JSON but the body could not be parsed as JSON. */
    MALFORMED_BODY
}
