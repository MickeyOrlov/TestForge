package io.testforge.api.fuzz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.api.explorer.ContractMismatch;
import io.testforge.api.explorer.ExplorableOperation;
import io.testforge.api.explorer.ResponseContractChecker;
import io.testforge.api.explorer.RuntimeExchange;
import java.util.List;

/**
 * Turns one response into one verdict.
 *
 * <p>The interesting judgement is the middle one. A {@code 500} is obviously a
 * finding and a documented {@code 400} obviously is not, but a {@code 200} for
 * a value the document forbids is the finding a generic fuzzer cannot produce:
 * it means the service is not enforcing its own contract, and every consumer
 * generated from that document is built on a promise nobody keeps.
 *
 * <p>Reflection is checked because a value that comes back verbatim is a
 * question worth asking — about escaping, about content types, about what else
 * the response will echo. The module reports it and stops there; deciding
 * whether it is exploitable is not a test framework's job.
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

    public Classification classify(ExplorableOperation operation, FuzzCase fuzzCase, RuntimeExchange exchange) {
        if (!exchange.completed()) {
            return new Classification(FuzzVerdict.TRANSPORT_FAILURE, List.of(), false);
        }

        List<ContractMismatch> mismatches = mismatches(operation, exchange);
        boolean reflected = reflected(fuzzCase, exchange);
        int status = exchange.status();

        if (status >= 500) {
            return new Classification(FuzzVerdict.SERVER_ERROR, mismatches, reflected);
        }
        if (fuzzCase.expectation() == FuzzExpectation.REJECT && status < 300) {
            return new Classification(FuzzVerdict.OVER_PERMISSIVE, mismatches, reflected);
        }
        if (!mismatches.isEmpty()) {
            return new Classification(FuzzVerdict.UNDOCUMENTED_RESPONSE, mismatches, reflected);
        }
        if (reflected) {
            return new Classification(FuzzVerdict.INPUT_REFLECTED, mismatches, true);
        }
        if (fuzzCase.expectation() == FuzzExpectation.ACCEPT && status >= 400 && status < 500) {
            return new Classification(FuzzVerdict.OVER_STRICT, mismatches, false);
        }
        return new Classification(FuzzVerdict.PASSED, mismatches, false);
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

    /** Verdict plus the evidence behind it. */
    public record Classification(
            FuzzVerdict verdict,
            List<ContractMismatch> mismatches,
            boolean inputReflected) {

        public Classification {
            mismatches = List.copyOf(mismatches == null ? List.of() : mismatches);
        }
    }
}
