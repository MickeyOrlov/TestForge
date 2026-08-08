package io.testforge.api.explorer;

/**
 * One parameter value the explorer decided to send, and where it came from.
 *
 * <p>{@code value} is stored redacted when the parameter name looks like a
 * credential — a configured API key passed as a query parameter is still a
 * credential when it lands in an artifact.
 */
public record ParameterBinding(String name, String in, ValueSource source, String value) {

    public boolean path() {
        return "path".equals(in);
    }

    public boolean query() {
        return "query".equals(in);
    }
}
