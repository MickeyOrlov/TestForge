package io.testforge.api.explorer;

/**
 * Sends one prepared request and reports what came back.
 *
 * <p>An interface rather than a class so the exploration logic stays plain Java
 * and testable without a server, and so a later replay stage can feed recorded
 * exchanges through the same pipeline that produced them.
 */
public interface ExchangeExecutor {

    RuntimeExchange execute(PreparedRequest request);

    /** Base URL requests are sent to, recorded on every observation. */
    String baseUrl();
}
