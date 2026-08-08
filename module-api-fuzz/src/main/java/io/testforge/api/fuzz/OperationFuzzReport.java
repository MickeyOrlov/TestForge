package io.testforge.api.fuzz;

import java.util.List;

/** Every case run against one operation, and how it went. */
public record OperationFuzzReport(
        String operationId,
        String operationKey,
        int cases,
        int findings,
        List<FuzzObservation> observations,
        String artifact,
        String skipReason) {

    public OperationFuzzReport {
        observations = List.copyOf(observations == null ? List.of() : observations);
    }

    public static OperationFuzzReport skipped(String operationId, String operationKey, String reason) {
        return new OperationFuzzReport(operationId, operationKey, 0, 0, List.of(), null, reason);
    }
}
