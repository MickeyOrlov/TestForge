package io.testforge.api.discovery;

import java.util.Locale;

/**
 * What one endpoint answered.
 *
 * <p>This is the only place in the module where a real payload exists, and it
 * exists for exactly one iteration of the discovery loop: the record is never
 * serialized, never stored, never logged. Everything that outlives the run is
 * derived from it — the shape map, the drift verdict — and carries types only.
 *
 * <p>{@code error} covers both a transport failure and a response the module
 * refused to read because it exceeded {@code probe.max-response-bytes}; in
 * either case there is nothing to snapshot.
 */
public record ResponseSample(
        int status,
        String contentType,
        String body,
        long durationMillis,
        String error) {

    public static ResponseSample failed(String error, long durationMillis) {
        return new ResponseSample(0, null, null, durationMillis, error);
    }

    public boolean ok() {
        return error == null;
    }

    public boolean json() {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).contains("json");
    }
}
