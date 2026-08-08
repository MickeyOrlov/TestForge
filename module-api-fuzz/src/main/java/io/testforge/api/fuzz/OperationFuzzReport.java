package io.testforge.api.fuzz;

import java.util.List;

/**
 * Every case run against one operation, what the control request proved, and
 * which of the document's promises were actually tested.
 *
 * <p>The control sits here rather than on each observation because it is run
 * once per operation. Every case below it was interpreted in its light, and a
 * reader who sees {@code control: 401 BLOCKED} knows immediately why the cases
 * beneath it conclude nothing.
 */
public record OperationFuzzReport(
        String operationId,
        String operationKey,
        ControlResult control,
        int cases,
        int findings,
        int inconclusive,
        List<FuzzObservation> observations,
        ConstraintCoverage coverage,
        String artifact,
        String skipReason) {

    public OperationFuzzReport {
        observations = List.copyOf(observations == null ? List.of() : observations);
        coverage = coverage == null ? ConstraintCoverage.none() : coverage;
    }

    public static OperationFuzzReport skipped(String operationId, String operationKey, String reason) {
        return new OperationFuzzReport(operationId, operationKey, null, 0, 0, 0,
                List.of(), ConstraintCoverage.none(), null, reason);
    }

    /** Skipped because the control request itself never worked. */
    public static OperationFuzzReport controlFailed(String operationId, String operationKey,
                                                    ControlResult control, ConstraintCoverage coverage) {
        return new OperationFuzzReport(operationId, operationKey, control, 0, 0, 0,
                List.of(), coverage, null, "control request not accepted: " + control.reason());
    }
}
