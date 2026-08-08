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
 * <p>{@code verdict} says only what can be concluded about validation.
 * {@code evidence} carries every independent fact about the response — a crash,
 * an echo, an undocumented shape, an infrastructure answer — so a response with
 * several problems reports all of them instead of the highest-ranked one.
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
        List<FuzzEvidence> evidence,
        String reason,
        List<ContractMismatch> mismatches,
        String error,
        String requestFragment,
        ConfirmationResult confirmation,
        ShrinkOutcome shrink) {

    public FuzzObservation {
        evidence = List.copyOf(evidence == null ? List.of() : evidence);
        mismatches = List.copyOf(mismatches == null ? List.of() : mismatches);
        confirmation = confirmation == null ? ConfirmationResult.notConfirmed() : confirmation;
        shrink = shrink == null ? ShrinkOutcome.notAttempted() : shrink;
    }

    /** The same observation with what confirmation and minimization learned. */
    public FuzzObservation analysed(ConfirmationResult confirmation, ShrinkOutcome shrink) {
        return new FuzzObservation(fuzzCase, resolvedUrl, verdict, expectation, status, contentType,
                responseBody, durationMillis, evidence, reason, mismatches, error, requestFragment,
                confirmation, shrink);
    }

    public boolean flaky() {
        return confirmation.reproducibility() == Reproducibility.FLAKY;
    }

    /** A finding that confirmation showed was not there after all. */
    public boolean disappeared() {
        return confirmation.reproducibility() == Reproducibility.DISAPPEARED;
    }

    /** A case that could not be applied to this operation's baseline. */
    public static FuzzObservation notApplicable(FuzzCase fuzzCase, String resolvedUrl) {
        return new FuzzObservation(fuzzCase, resolvedUrl, FuzzVerdict.NOT_APPLICABLE, fuzzCase.expectation(),
                null, null, null, null, List.of(), "the case does not apply to the baseline body",
                List.of(), null, fuzzCase.parameterName() + " <not applicable>",
                ConfirmationResult.notConfirmed(), ShrinkOutcome.notAttempted());
    }

    /**
     * A wrong validation decision, or a response fact worth reporting on its
     * own. An inconclusive case is neither: that is the run admitting it could
     * not tell, which is information but not a defect.
     */
    public boolean finding() {
        return verdict.finding() || evidence.stream().anyMatch(item -> item.kind().reportable());
    }

    public boolean has(FuzzEvidenceKind kind) {
        return evidence.stream().anyMatch(item -> item.kind() == kind);
    }
}
