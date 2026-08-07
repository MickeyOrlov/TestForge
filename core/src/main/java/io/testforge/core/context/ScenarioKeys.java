package io.testforge.core.context;

/**
 * Correlation ids that more than one module needs to agree on.
 *
 * <p>These keys live in the core precisely because they are hand-offs: one
 * module writes the value, another reads it. {@code module-mock} generates a
 * scope id when a scenario opens a {@code MockScope}; {@code module-http}
 * embeds that id into outgoing requests so they land on the scenario's own
 * stubs. Neither module depends on the other — they only share this key.
 *
 * <p>Project-specific keys do not belong here. Declare those as constants next
 * to the steps or tests that produce them.
 */
public final class ScenarioKeys {

    /**
     * Scenario id that ties an outgoing request to the stubs registered for
     * this scenario on a shared mock server. Written by
     * {@code ScopedMockClient.scope()}, read by the HTTP scope filter.
     */
    public static final ContextKey<String> TEST_SCOPE = ContextKey.of("TEST_SCOPE", String.class);

    /**
     * Per-scenario request id sent on every HTTP request. Kept in the context
     * so later assertions (service logs, traces, audit tables) can search by
     * the same value the requests carried.
     */
    public static final ContextKey<String> CORRELATION_ID = ContextKey.of("CORRELATION_ID", String.class);

    private ScenarioKeys() {
    }
}
