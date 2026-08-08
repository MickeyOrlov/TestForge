package io.testforge.api.fuzz;

import java.util.Comparator;
import java.util.List;

/**
 * What has to stay true for two responses to count as the same finding.
 *
 * <p>Both confirmation and shrinking need an answer to "is this still the same
 * defect", and "the status code matched" is not it. A {@code 500} that became a
 * {@code 500} for a different reason, or an {@code OVER_PERMISSIVE} that became
 * a plain success because the shrink removed the field being tested, would both
 * pass a naive comparison and both be wrong.
 *
 * <p>So the signature is explicit and small: the validation verdict, the
 * strongest independent fact about the response, and the status family. Two
 * observations are the same finding when all three agree. Nothing about
 * timing, body text or header order enters into it — those change between
 * identical requests and would make every finding look flaky.
 */
public record FindingSignature(
        FuzzVerdict verdict,
        FuzzEvidenceKind primaryEvidence,
        Integer statusFamily) {

    /**
     * Evidence ranked so the signature picks the same fact every time. A crash
     * outranks an undocumented shape; a transport failure outranks both,
     * because there was no response to describe.
     */
    private static final List<FuzzEvidenceKind> RANKED = List.of(
            FuzzEvidenceKind.TRANSPORT_FAILURE,
            FuzzEvidenceKind.SERVER_ERROR,
            FuzzEvidenceKind.UNDOCUMENTED_RESPONSE,
            FuzzEvidenceKind.INPUT_REFLECTED);

    public static FindingSignature of(FuzzObservation observation) {
        FuzzEvidenceKind primary = observation.evidence().stream()
                .map(FuzzEvidence::kind)
                .filter(RANKED::contains)
                .min(Comparator.comparingInt(RANKED::indexOf))
                .orElse(null);

        // 5 for any 5xx, 4 for any 4xx, 2 for any 2xx: a service that answers
        // 400 on one attempt and 422 on the next is refusing either way
        Integer family = observation.status() == null ? null : observation.status() / 100;
        return new FindingSignature(observation.verdict(), primary, family);
    }

    /** True when this observation shows the same defect as {@code other}. */
    public boolean matches(FindingSignature other) {
        return equals(other);
    }

    /** Something worth confirming and shrinking, as opposed to a healthy result. */
    public boolean worthChasing() {
        return verdict.finding() || (primaryEvidence != null && primaryEvidence.reportable());
    }

    @Override
    public String toString() {
        return "%s/%s/%s".formatted(verdict,
                primaryEvidence == null ? "-" : primaryEvidence,
                statusFamily == null ? "-" : statusFamily + "xx");
    }
}
