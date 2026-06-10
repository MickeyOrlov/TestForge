package io.testforge.core.context;

/**
 * Typed key for {@link ScenarioContext}. Declare keys as constants close to the
 * steps/tests that produce them:
 *
 * <pre>{@code
 * public static final ContextKey<String> REQUEST_ID = ContextKey.of("REQUEST_ID", String.class);
 * }</pre>
 */
public record ContextKey<T>(String name, Class<T> type) {

    public static <T> ContextKey<T> of(String name, Class<T> type) {
        return new ContextKey<>(name, type);
    }
}
