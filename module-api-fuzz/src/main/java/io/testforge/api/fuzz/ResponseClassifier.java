package io.testforge.api.fuzz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.api.explorer.ContractMismatch;
import io.testforge.api.explorer.ExplorableOperation;
import io.testforge.api.explorer.ResponseContractChecker;
import io.testforge.api.explorer.RuntimeExchange;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a <em>pair</em> of responses into a verdict: what the operation
 * answered with valid data, and what it answered with one field changed.
 *
 * <p>The pair is the whole point. A {@code 401} to an invalid payload looks
 * like successful validation until you notice the valid payload got {@code 401}
 * too. v1.1 read the mutated response alone and would have called that a
 * passing validation case — the defect this version exists to remove.
 *
 * <p>The rule underneath every branch: never claim more than was proven. If the
 * control was not accepted, or the mutated request was answered by
 * infrastructure rather than by the handler, the verdict is
 * {@link FuzzVerdict#INCONCLUSIVE} and the reason is recorded. Evidence about
 * the response — a crash, an echo, an undocumented shape — is collected
 * regardless, because those facts are true whether or not validation can be
 * judged.
 */
public class ResponseClassifier {

    /** Below this a reflected value is coincidence, not an echo. */
    private static final int MIN_REFLECTION_LENGTH = 6;

    private final ResponseContractChecker contractChecker;
    private final ObjectMapper objectMapper;

    public ResponseClassifier(ResponseContractChecker contractChecker, ObjectMapper objectMapper) {
        this.contractChecker = contractChecker;
        this.objectMapper = objectMapper;
    }

    public Classification classify(ExplorableOperation operation, ControlResult control,
                                   FuzzCase fuzzCase, RuntimeExchange exchange) {

        List<FuzzEvidence> evidence = new ArrayList<>();

        if (!exchange.completed()) {
            evidence.add(FuzzEvidence.of(FuzzEvidenceKind.TRANSPORT_FAILURE, exchange.error()));
            // the control reached the service and this did not: that is a fact
            // about the mutation, but it is not a validation verdict
            return new Classification(FuzzVerdict.INCONCLUSIVE, evidence,
                    "the mutated request did not complete", List.of());
        }

        int status = exchange.status();
        List<ContractMismatch> mismatches = mismatches(operation, exchange);
        boolean reflected = reflected(fuzzCase, exchange);

        if (HttpFacts.serverError(status)) {
            evidence.add(FuzzEvidence.of(FuzzEvidenceKind.SERVER_ERROR,
                    "status %d for a request the document describes".formatted(status)));
        }
        if (!mismatches.isEmpty()) {
            evidence.add(FuzzEvidence.of(FuzzEvidenceKind.UNDOCUMENTED_RESPONSE,
                    mismatches.getFirst().kind() + " at " + mismatches.getFirst().location()));
        }
        if (reflected) {
            evidence.add(FuzzEvidence.of(FuzzEvidenceKind.INPUT_REFLECTED,
                    "the mutated value came back in the response body"));
        }

        return new Classification(verdict(control, fuzzCase, status, evidence),
                evidence, inconclusiveReason(control, status), mismatches);
    }

    private FuzzVerdict verdict(ControlResult control, FuzzCase fuzzCase, int status, List<FuzzEvidence> evidence) {
        if (!control.conclusive()) {
            evidence.add(FuzzEvidence.of(FuzzEvidenceKind.CONTROL_NOT_ACCEPTED, control.reason()));
            return FuzzVerdict.INCONCLUSIVE;
        }
        if (HttpFacts.infrastructure(status)) {
            // the control got through and this did not: a gateway, a rate
            // limiter or a redirect answered, so nothing was validated
            evidence.add(FuzzEvidence.of(FuzzEvidenceKind.INFRASTRUCTURE_RESPONSE,
                    "status %d did not come from validation".formatted(status)));
            return FuzzVerdict.INCONCLUSIVE;
        }
        if (HttpFacts.serverError(status)) {
            // a crash is recorded as evidence; what the service would have
            // decided about the value remains unknown
            return FuzzVerdict.INCONCLUSIVE;
        }

        boolean accepted = HttpFacts.success(status);
        boolean refused = HttpFacts.validationShaped(status);

        return switch (fuzzCase.expectation()) {
            case REJECT -> accepted ? FuzzVerdict.OVER_PERMISSIVE
                    : refused ? FuzzVerdict.PASSED : FuzzVerdict.INCONCLUSIVE;
            case ACCEPT -> refused ? FuzzVerdict.OVER_STRICT
                    : accepted ? FuzzVerdict.PASSED : FuzzVerdict.INCONCLUSIVE;
            // the document said nothing, so no status proves anything either way
            case UNSPECIFIED -> FuzzVerdict.PASSED;
        };
    }

    private String inconclusiveReason(ControlResult control, int status) {
        if (!control.conclusive()) {
            return "control %s: %s".formatted(control.outcome(), control.reason());
        }
        if (HttpFacts.infrastructure(status)) {
            return "status %d came from infrastructure, not validation".formatted(status);
        }
        if (HttpFacts.serverError(status)) {
            return "the service crashed, so its validation decision is unknown";
        }
        return null;
    }

    /** A checker that throws must not turn one bad response into a broken run. */
    private List<ContractMismatch> mismatches(ExplorableOperation operation, RuntimeExchange exchange) {
        try {
            return contractChecker.check(operation, exchange);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Compares against decoded string values when the body is JSON, not against
     * the raw text. A probe containing a quote comes back escaped, so a naive
     * substring search over the serialized document would miss exactly the
     * values most worth detecting.
     */
    private boolean reflected(FuzzCase fuzzCase, RuntimeExchange exchange) {
        String value = fuzzCase.value();
        String body = exchange.responseBody();
        if (value == null || body == null || value.length() < MIN_REFLECTION_LENGTH) {
            return false;
        }

        try {
            JsonNode root = objectMapper.readTree(body);
            if (root != null && !root.isMissingNode()) {
                return containsText(root, value);
            }
        } catch (Exception e) {
            // not JSON — the raw comparison below is the right one
        }
        return body.contains(value);
    }

    private boolean containsText(JsonNode node, String value) {
        if (node.isTextual()) {
            return node.asText().contains(value);
        }
        for (JsonNode child : node) {
            if (containsText(child, value)) {
                return true;
            }
        }
        return false;
    }

    /** The verdict, why it is what it is, and every fact behind it. */
    public record Classification(
            FuzzVerdict verdict,
            List<FuzzEvidence> evidence,
            String reason,
            List<ContractMismatch> mismatches) {

        public Classification {
            evidence = List.copyOf(evidence == null ? List.of() : evidence);
            mismatches = List.copyOf(mismatches == null ? List.of() : mismatches);
        }

        public boolean has(FuzzEvidenceKind kind) {
            return evidence.stream().anyMatch(item -> item.kind() == kind);
        }
    }
}
