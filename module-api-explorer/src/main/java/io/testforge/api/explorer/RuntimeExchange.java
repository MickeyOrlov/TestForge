package io.testforge.api.explorer;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Both sides of one call, as they actually went over the wire — before
 * anything is interpreted or redacted.
 *
 * <p>The request side is captured rather than reconstructed: by the time a
 * request leaves {@code ApiClient} it carries a correlation id, whatever an
 * {@code ApiRequestCustomizer} added, and any project filter's headers. An
 * observation that showed only what the explorer itself set would be a
 * plausible fiction, and useless for reproducing the call.
 *
 * <p>This is the only object holding raw bodies, and it lives for one iteration
 * of the run. Everything reaching an artifact is derived from it and redacted
 * on the way.
 */
public record RuntimeExchange(
        Map<String, String> requestHeaders,
        String requestBody,
        int status,
        String contentType,
        Map<String, String> responseHeaders,
        String responseBody,
        long durationMillis,
        String error) {

    public RuntimeExchange {
        requestHeaders = sorted(requestHeaders);
        responseHeaders = sorted(responseHeaders);
    }

    public static RuntimeExchange failed(Map<String, String> requestHeaders, String error, long durationMillis) {
        return new RuntimeExchange(requestHeaders, null, 0, null, Map.of(), null, durationMillis, error);
    }

    public boolean completed() {
        return error == null;
    }

    private static Map<String, String> sorted(Map<String, String> headers) {
        return Collections.unmodifiableMap(new TreeMap<>(headers == null ? Map.of() : headers));
    }
}
