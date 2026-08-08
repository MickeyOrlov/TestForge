package io.testforge.api.fuzz;

/**
 * What happened when the operation was called with data the document says is
 * entirely valid.
 *
 * <p>This is the question that has to be answered before any mutation means
 * anything. If a valid request already gets a {@code 401}, then an invalid one
 * getting a {@code 401} proves nothing about validation — and v1.1 would have
 * called it a passing validation case.
 *
 * <p>Only {@link #ACCEPTED} licenses a conclusion about validation behaviour.
 * Everything else makes the operation's cases inconclusive, with the reason
 * recorded so a reader knows what to fix before running again.
 */
public enum ControlOutcome {

    /** 2xx. The operation is reachable with valid data; mutations are interpretable. */
    ACCEPTED,

    /**
     * The service refused data the document calls valid — 400 or 422. Either
     * the endpoint needs state this module does not create, or the document
     * describes something the service does not implement. Either way, nothing
     * can be concluded from a mutation also being refused.
     */
    REJECTED,

    /**
     * 401, 403, 429, or a redirect. The request never reached the handler's
     * validation, so no response to it is evidence about validation. This is
     * the case that most often masquerades as success.
     */
    BLOCKED,

    /** 5xx. The endpoint is broken independently of what is sent to it. */
    FAILED,

    /** The control request never completed at all. */
    UNREACHABLE;

    /** Only an accepted control lets the run draw conclusions about validation. */
    public boolean conclusive() {
        return this == ACCEPTED;
    }
}
