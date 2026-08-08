package io.testforge.api.explorer;

/**
 * Where a request value came from, in priority order.
 *
 * <p>Recorded on every binding because it is the difference between "the API
 * works" and "the API works for the one id somebody hard-coded". It is also
 * the hook a later stateful stage needs: a value produced by another
 * operation's response would arrive as a new source without changing anything
 * that reads this enum today.
 */
public enum ValueSource {

    /** Supplied by a human in {@code forge.api-explorer.parameters}. */
    CONFIGURED,

    /** The parameter's own {@code example}, or the first of its {@code examples}. */
    EXAMPLE,

    /** The schema's {@code default}. */
    DEFAULT,

    /** The first entry of the schema's {@code enum}. */
    ENUM,

    /** Derived from the declared type and format. Deterministic, never random. */
    GENERATED
}
