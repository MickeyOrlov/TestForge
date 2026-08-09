package io.testforge.api.fuzz;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Everything needed to come back to one finding later, and nothing that should
 * not be written down.
 *
 * <p>Deliberately not a replay engine. It records what the run did and what it
 * did it against; re-running is still {@code only-cases} plus the seed. The
 * piece that makes it worth writing is {@code specFingerprint}: a reproduction
 * against a document that has since changed is not a reproduction, and without
 * the fingerprint nobody would notice.
 *
 * <p>Resolved inputs pass through the same redaction as everything else, so a
 * manifest never carries a credential even when one was configured as a
 * parameter.
 */
public record ReproductionManifest(
        String caseId,
        long seed,
        String specId,
        String specFingerprint,
        String operationKey,
        String operationId,
        String location,
        FuzzCaseKind mutation,
        String constraint,
        FuzzExpectation expectation,
        ControlOutcome controlOutcome,
        Integer controlStatus,
        Integer fuzzStatus,
        FuzzVerdict verdict,
        List<FuzzEvidenceKind> evidence,
        Map<String, String> resolvedInputs) {

    public ReproductionManifest {
        evidence = List.copyOf(evidence == null ? List.of() : evidence);
        resolvedInputs = Map.copyOf(resolvedInputs == null ? Map.of() : resolvedInputs);
    }

    static ReproductionManifest of(FuzzObservation observation, ControlResult control,
                                   String specId, String specFingerprint, long seed,
                                   Map<String, String> resolvedInputs) {

        FuzzCase fuzzCase = observation.fuzzCase();
        return new ReproductionManifest(
                fuzzCase.id(),
                seed,
                specId,
                specFingerprint,
                fuzzCase.operationKey(),
                fuzzCase.operationId(),
                fuzzCase.location(),
                fuzzCase.kind(),
                fuzzCase.constraint(),
                fuzzCase.expectation(),
                control.outcome(),
                control.status(),
                observation.status(),
                observation.verdict(),
                observation.evidence().stream().map(FuzzEvidence::kind).toList(),
                new TreeMap<>(resolvedInputs));
    }
}
