package io.testforge.core.context;

/**
 * Names under which one module leaves a note on an outgoing request for
 * another to read, through REST Assured's {@code FilterContext} values. The
 * modules never see each other; they share only these names.
 */
public final class RequestAttributes {

    /**
     * Who the request acts as and how the credential travels, e.g.
     * {@code admin (header Authorization)} or {@code anonymous (no credential)}.
     * Set by {@code module-auth}, shown by {@code module-http}'s failure
     * diagnostics. Never the credential itself.
     */
    public static final String AUTH = "testforge.auth";

    private RequestAttributes() {
    }
}
