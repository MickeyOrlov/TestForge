package io.testforge.api.explorer;

import io.testforge.http.Redactor;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds observations, and is the only place that decides what of a real
 * exchange is allowed to reach a file.
 *
 * <p>Redaction reuses {@code module-http}'s {@link Redactor}, so a project
 * configures its credential field names once, under
 * {@code forge.http.logging.redact-*}, and both the request log and the
 * exploration artifacts honour them. A second list would drift, and the one
 * that drifted would be the one that leaked.
 */
public class ObservationFactory {

    private static final String MASK = "***";

    private final Redactor redactor;
    private final int maxBodyChars;

    public ObservationFactory(Redactor redactor, int maxBodyChars) {
        this.redactor = redactor;
        this.maxBodyChars = maxBodyChars;
    }

    public ApiObservation skipped(ExplorableOperation operation, String baseUrl, PlannedRequest plan) {
        return new ApiObservation(
                operation.specId(),
                operation.operationId(),
                operation.method(),
                operation.pathTemplate(),
                baseUrl + operation.pathTemplate(),
                operation.deprecated(),
                Instant.now().toString(),
                redactBindings(plan.bindings()),
                Map.of(),
                null,
                null,
                null,
                Map.of(),
                null,
                null,
                ExplorerOutcome.SKIPPED,
                plan.skipReason(),
                detail(plan),
                List.of());
    }

    public ApiObservation executed(
            ExplorableOperation operation,
            String baseUrl,
            PlannedRequest plan,
            RuntimeExchange exchange,
            List<ContractMismatch> mismatches) {

        boolean failed = !exchange.completed();
        ExplorerOutcome outcome = failed
                ? ExplorerOutcome.FAILED
                : mismatches.isEmpty() ? ExplorerOutcome.PASSED : ExplorerOutcome.CONTRACT_MISMATCH;

        return new ApiObservation(
                operation.specId(),
                operation.operationId(),
                operation.method(),
                operation.pathTemplate(),
                baseUrl + plan.request().resolvedTarget(),
                operation.deprecated(),
                Instant.now().toString(),
                redactBindings(plan.bindings()),
                redactHeaders(exchange.requestHeaders()),
                body(exchange.requestBody()),
                failed ? null : exchange.status(),
                exchange.contentType(),
                redactHeaders(exchange.responseHeaders()),
                body(exchange.responseBody()),
                exchange.durationMillis(),
                outcome,
                null,
                failed ? exchange.error() : null,
                mismatches);
    }

    private String detail(PlannedRequest plan) {
        if (plan.skipReason() == null) {
            return null;
        }
        return plan.detail() == null || plan.detail().isBlank()
                ? plan.skipReason().description()
                : "%s: %s".formatted(plan.skipReason().description(), plan.detail());
    }

    private Map<String, String> redactHeaders(Map<String, String> headers) {
        Map<String, String> redacted = new LinkedHashMap<>();
        headers.forEach((name, value) -> redacted.put(name, redactor.header(name, value)));
        return redacted;
    }

    private List<ParameterBinding> redactBindings(List<ParameterBinding> bindings) {
        return bindings.stream()
                .map(binding -> sensitive(binding.name())
                        ? new ParameterBinding(binding.name(), binding.in(), binding.source(), MASK)
                        : binding)
                .toList();
    }

    /**
     * A configured API key passed as a query parameter is still a credential.
     * Rather than keeping a third list of sensitive names, ask the redactor
     * whether it would mask a field of this name — with a dummy value, so no
     * real one is ever built into the probe.
     */
    private boolean sensitive(String name) {
        if (!MASK.equals(redactor.header(name, "probe"))) {
            String escaped = name.replace("\\", "\\\\").replace("\"", "\\\"");
            return redactor.body("{\"%s\":\"probe\"}".formatted(escaped)).contains(MASK);
        }
        return true;
    }

    private String body(String body) {
        if (body == null || body.isBlank()) {
            return body;
        }
        String redacted = redactor.body(body);
        if (redacted.length() <= maxBodyChars) {
            return redacted;
        }
        return redacted.substring(0, maxBodyChars)
                + "... <truncated, %d chars total>".formatted(redacted.length());
    }
}
