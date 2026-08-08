package io.testforge.api.explorer;

/**
 * Why an operation was not called. Recorded on the observation so the report
 * distinguishes "left alone on purpose" from "we could not work out how".
 */
public enum SkipReason {

    /** The method is outside {@code forge.api-explorer.methods}. */
    METHOD_NOT_ENABLED("method is not enabled for exploration"),

    /** The method is not safe and {@code allow-unsafe-methods} is off. */
    UNSAFE_METHOD_NOT_ALLOWED("method requires forge.api-explorer.allow-unsafe-methods"),

    PATH_EXCLUDED("path matches an exclude pattern"),

    PATH_NOT_INCLUDED("path does not match any include pattern"),

    /** A path parameter had no value and none could be derived from the document. */
    MISSING_PATH_PARAMETER("no value available for a required path parameter"),

    /** A required query parameter had no value and none could be derived. */
    MISSING_REQUIRED_QUERY_PARAMETER("no value available for a required query parameter"),

    /** The operation needs a request body the explorer will not invent in v1. */
    REQUEST_BODY_REQUIRED("the operation requires a request body"),

    MAX_OPERATIONS_REACHED("forge.api-explorer.max-operations reached");

    private final String description;

    SkipReason(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
