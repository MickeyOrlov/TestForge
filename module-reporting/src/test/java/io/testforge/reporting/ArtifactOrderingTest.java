package io.testforge.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.artifact.TestArtifact;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the comparator to its DOCUMENTED key sequence, not merely to "is a total order".
 *
 * <p>Review B (agy-c, finding B1-6) showed the existing determinism check in
 * {@code ReportingParallelSafetyTest} cannot tell those apart: it shuffles the same
 * artifacts twice and asserts the two manifests are byte-identical, which any total
 * order satisfies. Sorting by {@code file} alone — a total order on the wrong field —
 * would pass it while silently discarding the intended
 * {@code createdAt → source → category → name → file} ordering.
 */
class ArtifactOrderingTest {

    private static TestArtifact artifact(Instant createdAt, String source, String category, String name, String file) {
        return new TestArtifact(source, category, name, Path.of(file), "text/plain", createdAt, Map.of());
    }

    @Test
    void ordersByCreatedAtFirst() {
        Instant early = Instant.parse("2026-01-01T00:00:00Z");
        Instant late = Instant.parse("2026-01-02T00:00:00Z");

        // The later artifact sorts first on every OTHER key, so only createdAt
        // taking precedence can produce the expected order.
        TestArtifact newer = artifact(late, "aaa", "aaa", "aaa", "aaa.txt");
        TestArtifact older = artifact(early, "zzz", "zzz", "zzz", "zzz.txt");

        List<TestArtifact> sorted = new ArrayList<>(List.of(newer, older));
        sorted.sort(ArtifactOrdering.DETERMINISTIC);

        assertThat(sorted).containsExactly(older, newer);
    }

    @Test
    void breaksCreatedAtTiesBySourceThenCategoryThenName() {
        Instant sameInstant = Instant.parse("2026-01-01T00:00:00Z");

        TestArtifact a = artifact(sameInstant, "module-a", "cat-z", "name-z", "z.txt");
        TestArtifact b = artifact(sameInstant, "module-b", "cat-a", "name-a", "a.txt");
        TestArtifact c = artifact(sameInstant, "module-a", "cat-a", "name-z", "z.txt");
        TestArtifact d = artifact(sameInstant, "module-a", "cat-a", "name-a", "z.txt");

        List<TestArtifact> sorted = new ArrayList<>(List.of(a, b, c, d));
        sorted.sort(ArtifactOrdering.DETERMINISTIC);

        // source wins over category and name; category wins over name.
        assertThat(sorted).containsExactly(d, c, a, b);
    }

    @Test
    void usesFileAsTheFinalTiebreakerSoTheOrderIsTotal() {
        Instant sameInstant = Instant.parse("2026-01-01T00:00:00Z");

        TestArtifact second = artifact(sameInstant, "m", "c", "n", "b.txt");
        TestArtifact first = artifact(sameInstant, "m", "c", "n", "a.txt");

        List<TestArtifact> sorted = new ArrayList<>(List.of(second, first));
        sorted.sort(ArtifactOrdering.DETERMINISTIC);

        assertThat(sorted).containsExactly(first, second);
    }

    @Test
    void sortingByFileAloneWouldNotSatisfyThisTest() {
        // Guards the guard: this is the exact substitution the shuffle-based
        // determinism assertion cannot detect.
        Instant early = Instant.parse("2026-01-01T00:00:00Z");
        Instant late = Instant.parse("2026-01-02T00:00:00Z");

        TestArtifact newerButAlphabeticallyFirst = artifact(late, "m", "c", "n", "a.txt");
        TestArtifact olderButAlphabeticallyLast = artifact(early, "m", "c", "n", "z.txt");

        List<TestArtifact> sorted = new ArrayList<>(List.of(newerButAlphabeticallyFirst, olderButAlphabeticallyLast));
        sorted.sort(ArtifactOrdering.DETERMINISTIC);

        assertThat(sorted).containsExactly(olderButAlphabeticallyLast, newerButAlphabeticallyFirst);
    }
}
