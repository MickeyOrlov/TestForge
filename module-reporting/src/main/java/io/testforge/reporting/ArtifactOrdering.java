package io.testforge.reporting;

import io.testforge.artifact.TestArtifact;
import java.util.Comparator;

/**
 * The single deterministic ordering for artifacts.
 *
 * <p>CI output must be byte-identical across runs given the same artifacts, so both the
 * in-memory snapshot and the serialised manifest have to sort the same way. This class
 * exists so there is exactly one definition: the comparator was previously duplicated in
 * {@link RunArtifactSink} and {@link ArtifactManifestWriter}, the two copies drifted, and
 * one of them was left without a total order.
 *
 * <p>{@code file} is the final tiebreaker, which is what makes the order total —
 * {@code createdAt}, {@code source}, {@code category} and {@code name} can all coincide
 * for two distinct artifacts.
 */
final class ArtifactOrdering {

    static final Comparator<TestArtifact> DETERMINISTIC = Comparator
            .comparing(TestArtifact::createdAt, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(TestArtifact::source, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(TestArtifact::category, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(TestArtifact::name, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(a -> a.file() != null ? a.file().toString() : "",
                    Comparator.nullsFirst(Comparator.naturalOrder()));

    private ArtifactOrdering() {
    }
}
