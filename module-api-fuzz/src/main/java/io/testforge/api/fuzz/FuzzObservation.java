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
 *
 * <p>{@code requestFragment} is the smallest readable statement of what was
 * changed — {@code $.profile.age = 17} — so a reader does not have to
 * reconstruct it from the URL and the case id.
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
        String error,
        String requestFragment) {

    public FuzzObservation {
        mismatches = List.copyOf(mismatches == null ? List.of() : mismatches);
    }

    /** A case that could not be applied to this operation's baseline. */
    public static FuzzObservation notApplicable(FuzzCase fuzzCase, String resolvedUrl) {
        return new FuzzObservation(fuzzCase, resolvedUrl, FuzzVerdict.NOT_APPLICABLE, fuzzCase.expectation(),
                null, null, null, null, false, List.of(),
                "the case does not apply to the baseline body",
                fuzzCase.parameterName() + " <not applicable>");
    }

    public boolean finding() {
        return verdict.finding();
    }
}
