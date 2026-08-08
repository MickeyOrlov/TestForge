package io.testforge.api.fuzz;

import io.testforge.api.explorer.ContractMismatch;
import java.util.List;

/**
 * What one case did.
 *
 * <p>Carries the case alongside the result on purpose: a verdict without the
 * value that produced it is not reproducible, and reproducibility is the whole
 * point of a fuzz report. The value is redacted like anything else, so a case
 * built from a configured credential does not leak through the finding.
 */
public record FuzzObservation(
        FuzzCase fuzzCase,
        String resolvedUrl,
        FuzzVerdict verdict,
        FuzzExpectation expectation,
        Integer status,
        String contentType,
        String responseBody,
        Long durationMillis,
        boolean inputReflected,
        List<ContractMismatch> mismatches,
        String error) {

    public FuzzObservation {
        mismatches = List.copyOf(mismatches == null ? List.of() : mismatches);
    }

    public boolean finding() {
        return verdict.finding();
    }
}
