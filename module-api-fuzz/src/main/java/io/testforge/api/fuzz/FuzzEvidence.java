package io.testforge.api.fuzz;

/**
 * One independent fact about a response, recorded whatever the verdict
 * concluded.
 *
 * <p>A single response can carry several at once — a {@code 500} that also
 * echoes the input back and also has a shape the document never described. In
 * v1.1 the strongest of those won a priority contest and the rest were lost.
 * Here they all survive, and the verdict is left to say only what it can prove
 * about validation.
 */
public record FuzzEvidence(FuzzEvidenceKind kind, String detail) {

    public static FuzzEvidence of(FuzzEvidenceKind kind, String detail) {
        return new FuzzEvidence(kind, detail);
    }
}
