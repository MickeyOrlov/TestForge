package io.testforge.api.fuzz;

/**
 * Facts a response can carry. Independent of each other and of the verdict.
 */
public enum FuzzEvidenceKind {

    /** 5xx. Malformed input should be refused, not fatal — always worth reporting. */
    SERVER_ERROR(true),

    /** The request did not complete. */
    TRANSPORT_FAILURE(true),

    /** The status or body shape is not one the document describes. */
    UNDOCUMENTED_RESPONSE(true),

    /** The mutated value came back verbatim. */
    INPUT_REFLECTED(true),

    /**
     * The response came from authentication, rate limiting, routing or a
     * redirect — not from validation. Not a defect in itself, but the reason a
     * verdict cannot be drawn.
     */
    INFRASTRUCTURE_RESPONSE(false),

    /** The control request for this operation was not accepted. */
    CONTROL_NOT_ACCEPTED(false);

    private final boolean reportable;

    FuzzEvidenceKind(boolean reportable) {
        this.reportable = reportable;
    }

    /** Whether this fact belongs in the findings list on its own. */
    public boolean reportable() {
        return reportable;
    }
}
