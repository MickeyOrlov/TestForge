package io.testforge.api.fuzz;

/**
 * What one case found, in the order that matters when several apply.
 *
 * <p>Ordered deliberately: a crash outranks a validation gap, which outranks a
 * documentation gap, which outranks an echo. A report that leads with the
 * strongest signal is one people keep reading.
 */
public enum FuzzVerdict {

    /** The request never completed — connection dropped, timed out, unreadable. */
    TRANSPORT_FAILURE(true),

    /** 5xx. Malformed input should produce a 4xx; a 500 is the service failing, not refusing. */
    SERVER_ERROR(true),

    /** The document forbids this value and the service took it anyway. */
    OVER_PERMISSIVE(true),

    /** The response status or shape is not one the document describes. */
    UNDOCUMENTED_RESPONSE(true),

    /** The value came back verbatim in the response — an escaping question worth answering. */
    INPUT_REFLECTED(true),

    /** The value is valid per the document and the service refused it. */
    OVER_STRICT(true),

    /** The service did what the document implies it should. */
    PASSED(false);

    private final boolean finding;

    FuzzVerdict(boolean finding) {
        this.finding = finding;
    }

    public boolean finding() {
        return finding;
    }
}
