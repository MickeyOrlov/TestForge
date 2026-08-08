package io.testforge.api.explorer;

import java.util.List;

/**
 * The outcome of trying to build a request: either something sendable, or a
 * reason nobody could.
 *
 * <p>The bindings are kept in both cases. Knowing that four of five parameters
 * resolved and which one did not is the difference between a report that tells
 * a team what to configure and one that just says "skipped".
 */
public record PlannedRequest(
        PreparedRequest request,
        SkipReason skipReason,
        String detail,
        List<ParameterBinding> bindings) {

    public PlannedRequest {
        bindings = List.copyOf(bindings == null ? List.of() : bindings);
    }

    public static PlannedRequest of(PreparedRequest request, List<ParameterBinding> bindings) {
        return new PlannedRequest(request, null, null, bindings);
    }

    public static PlannedRequest skip(SkipReason reason, String detail, List<ParameterBinding> bindings) {
        return new PlannedRequest(null, reason, detail, bindings);
    }

    public boolean sendable() {
        return request != null;
    }
}
