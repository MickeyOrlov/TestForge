package io.testforge.api.explorer;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What one operation did, once. The unit this module produces and the unit
 * every later capability will build on.
 *
 * <p>The shape is chosen for what comes next as much as for what v1 needs:
 *
 * <ul>
 *   <li><b>Value extraction</b> — the redacted response body and the resolved
 *       identity are both here, so a rule like "the id in this response feeds
 *       that parameter" has something to read.</li>
 *   <li><b>Producer/consumer inference</b> — {@code parameters} records where
 *       every value came from. An inferred value becomes another
 *       {@link ValueSource}; nothing reading an observation has to change.</li>
 *   <li><b>Replay</b> — method, path template, resolved URL and parameter
 *       bindings are enough to rebuild the call. Credentials are deliberately
 *       not: they come from {@code ApiClient}'s customizers at replay time,
 *       which is also why they can be redacted here without loss.</li>
 *   <li><b>Stateful sequences</b> — {@code startedAt} and the per-operation
 *       identity give a run a total order, which a sequence needs and a set of
 *       independent calls does not.</li>
 * </ul>
 *
 * <p>Headers and bodies are already redacted and truncated when an observation
 * is constructed. Nothing downstream has to remember to do it.
 */
public record ApiObservation(
        String specId,
        String operationId,
        String method,
        String pathTemplate,
        String resolvedUrl,
        boolean deprecated,
        String startedAt,
        List<ParameterBinding> parameters,
        Map<String, String> requestHeaders,
        String requestBody,
        Integer status,
        String contentType,
        Map<String, String> responseHeaders,
        String responseBody,
        Long durationMillis,
        ExplorerOutcome outcome,
        SkipReason skipReason,
        String reason,
        List<ContractMismatch> mismatches) {

    public ApiObservation {
        parameters = List.copyOf(parameters == null ? List.of() : parameters);
        mismatches = List.copyOf(mismatches == null ? List.of() : mismatches);
        requestHeaders = sorted(requestHeaders);
        responseHeaders = sorted(responseHeaders);
    }

    /** {@code GET /api/v1/tasks} — stable across runs and environments. */
    public String key() {
        return method + " " + pathTemplate;
    }

    private static Map<String, String> sorted(Map<String, String> headers) {
        return Collections.unmodifiableMap(new TreeMap<>(headers == null ? Map.of() : headers));
    }
}
