package io.testforge.api.fuzz;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What the mutations that actually went out came back as.
 *
 * <p>Raw counts, deliberately. The temptation with a page of fuzz results is to
 * reduce it to one number — a hardening score, a percentage — and every such
 * number is wrong in the same way: it averages a crash together with an
 * unanswered probe, rewards an API for declaring fewer constraints, and invites
 * comparison between services whose documents have nothing in common. A reader
 * deciding what to fix needs to know how many cases were schema-proven invalid
 * and how many of those were accepted anyway. That is three numbers, and they
 * are three numbers here.
 *
 * <p>Split by category, because a protocol mutation exercises no declared
 * constraint and counting it alongside schema mutations would overstate how much
 * of the document was tested.
 */
public record MutationOutcomes(
        int schemaMutations,
        int protocolMutations,
        Map<FuzzExpectation, Integer> byExpectation,
        Map<FuzzVerdict, Integer> byVerdict,
        Map<FuzzEvidenceKind, Integer> byEvidence) {

    public MutationOutcomes {
        byExpectation = Map.copyOf(byExpectation == null ? Map.of() : byExpectation);
        byVerdict = Map.copyOf(byVerdict == null ? Map.of() : byVerdict);
        byEvidence = Map.copyOf(byEvidence == null ? Map.of() : byEvidence);
    }

    public static MutationOutcomes none() {
        return new MutationOutcomes(0, 0, Map.of(), Map.of(), Map.of());
    }

    public static MutationOutcomes of(List<FuzzObservation> observations) {
        Map<FuzzExpectation, Integer> byExpectation = new EnumMap<>(FuzzExpectation.class);
        Map<FuzzVerdict, Integer> byVerdict = new EnumMap<>(FuzzVerdict.class);
        Map<FuzzEvidenceKind, Integer> byEvidence = new EnumMap<>(FuzzEvidenceKind.class);
        int schema = 0;
        int protocol = 0;

        for (FuzzObservation observation : observations) {
            FuzzCaseCategory category = observation.fuzzCase().kind().category();
            if (category == FuzzCaseCategory.PROTOCOL_MUTATION) {
                protocol++;
            } else {
                schema++;
            }
            byExpectation.merge(observation.expectation(), 1, Integer::sum);
            byVerdict.merge(observation.verdict(), 1, Integer::sum);
            // one response can carry several independent facts, and each is
            // counted, so a crash that also echoed input shows up under both
            observation.evidence().forEach(evidence -> byEvidence.merge(evidence.kind(), 1, Integer::sum));
        }

        return new MutationOutcomes(schema, protocol, byExpectation, byVerdict, byEvidence);
    }

    public int total() {
        return schemaMutations + protocolMutations;
    }
}
