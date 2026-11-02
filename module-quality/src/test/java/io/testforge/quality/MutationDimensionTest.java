package io.testforge.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testforge.quality.QualityProperties.MutationProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The mutation dimension, read from PIT's mutations.xml. */
class MutationDimensionTest {

    @TempDir
    Path dir;

    private static String mutation(String status, String method, int line, String mutator, String description) {
        return "<mutation detected='" + !status.equals("SURVIVED") + "' status='" + status + "'>"
                + "<sourceFile>Prices.java</sourceFile><mutatedClass>io.acme.Prices</mutatedClass>"
                + "<mutatedMethod>" + method + "</mutatedMethod><methodDescription>()I</methodDescription>"
                + "<lineNumber>" + line + "</lineNumber>"
                + "<mutator>org.pitest.mutationtest.engine.gregor.mutators." + mutator + "</mutator>"
                + "<description>" + description + "</description></mutation>";
    }

    private void report(String module, String... mutations) throws IOException {
        Path file = dir.resolve(module).resolve("build/reports/pitest/mutations.xml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><mutations partial=\"true\">"
                + String.join("", mutations) + "</mutations>");
    }

    @Test
    void theScoreIsDetectedOverAllMutantsAndSurvivorsAreIssuesWithoutTheirLine() throws IOException {
        report("orders",
                mutation("KILLED", "total", 10, "MathMutator", "Replaced integer addition with subtraction"),
                mutation("TIMED_OUT", "total", 11, "IncrementsMutator", "Changed increment"),
                mutation("SURVIVED", "discount", 20, "ConditionalsBoundaryMutator", "changed conditional boundary"),
                mutation("NON_VIABLE", "discount", 21, "MathMutator", "never compiled"));
        report("billing",
                mutation("SURVIVED", "discount", 22, "ConditionalsBoundaryMutator", "changed conditional boundary"),
                mutation("NO_COVERAGE", "round", 30, "MathMutator", "Replaced division with multiplication"));

        QualityDimension mutation = MutationDimension.of(new MutationProperties(List.of(dir.toString()), 0.5));

        // 2 detected of 5 counted (NON_VIABLE left out): 40, below the minimum of 50
        assertThat(mutation.status()).isEqualTo(QualityStatus.FAIL);
        assertThat(mutation.score()).isEqualTo(40);
        assertThat(mutation.facts()).containsExactly(
                "5 mutants: 2 detected, 2 survived, 1 not covered by any test (test strength 50.0% on covered code)",
                "survived: io.acme.Prices.discount line 22 — changed conditional boundary",
                "survived: io.acme.Prices.discount line 20 — changed conditional boundary",
                "minimum 50.0% (forge.quality.mutation.min-score)");
        assertThat(mutation.issues()).as("two survivors of one method and mutator are one issue")
                .containsExactly("io.acme.Prices.discount ConditionalsBoundaryMutator");
        assertThat(MutationDimension.of(new MutationProperties(List.of(dir.toString()), null)).status())
                .as("without a minimum a survivor warns").isEqualTo(QualityStatus.WARN);
    }

    @Test
    void withoutAReportMutationIsNotMeasuredAndADoctypeIsRefused() throws IOException {
        QualityDimension none = MutationDimension.of(new MutationProperties(List.of(dir.toString()), null));
        assertThat(none.status()).isEqualTo(QualityStatus.NOT_MEASURED);
        assertThat(none.facts().getFirst()).startsWith("no PIT mutations.xml under [" + dir);

        Path file = dir.resolve("mutations.xml");
        Files.writeString(file, "<?xml version=\"1.0\"?><!DOCTYPE m [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<mutations>&x;</mutations>");
        assertThatThrownBy(() -> MutationDimension.of(new MutationProperties(List.of(file.toString()), null)))
                .isInstanceOf(QualityException.class).hasMessageStartingWith("Could not read the PIT report");
        assertThatThrownBy(() -> new MutationProperties(null, 60.0)).isInstanceOf(IllegalArgumentException.class);
    }
}
