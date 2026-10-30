package io.testforge.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.artifact.TestArtifact;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Markdown index must lead a CI reader to the report and say whether it passed. */
class ArtifactSummaryActionabilityTest {

    private static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");

    private final ArtifactSummaryWriter writer = new ArtifactSummaryWriter();

    private static TestArtifact artifact(String source, String name, Path file, Map<String, String> metadata) {
        return new TestArtifact(source, "report", name, file, "text/markdown", NOW, metadata);
    }

    private String summary(Path runRoot, List<TestArtifact> artifacts) throws Exception {
        Files.createDirectories(runRoot);
        assertThat(writer.writeSummary(runRoot, "run-1", artifacts, List.of())).isTrue();
        return Files.readString(runRoot.resolve("summary.md"));
    }

    @Test
    void anExternalReportShowsWhereItActuallyLives(@TempDir Path temp) throws Exception {
        Path runRoot = temp.resolve("artifacts/run-1");
        String content = summary(runRoot, List.of(artifact("module-api-discovery", "report.md",
                Path.of("build/api-discovery/report.md"),
                Map.of("location", "build/api-discovery/report.md", "healthy", "true"))));

        assertThat(content).contains(
                "- **report.md** (report): `build/api-discovery/report.md` (outside the run directory) — healthy: true");
    }

    @Test
    void aFileInsideTheRunKeepsItsRunRelativePath(@TempDir Path temp) throws Exception {
        Path runRoot = temp.resolve("run-1");
        Path inside = runRoot.resolve("module-flow/path.txt");
        Files.createDirectories(inside.getParent());
        Files.createFile(inside);

        String content = summary(runRoot, List.of(artifact("module-flow", "path", inside,
                Map.of("location", "build/whatever/path.txt"))));

        assertThat(content).contains("- **path** (report): `module-flow/path.txt`")
                .doesNotContain("outside the run directory");
    }

    @Test
    void locationsThatCouldLeakTheLocalLayoutAreIgnored(@TempDir Path temp) throws Exception {
        Path runRoot = temp.resolve("run-1");
        String content = summary(runRoot, List.of(
                artifact("p", "abs", Path.of("x/abs.md"), Map.of("location", temp.toAbsolutePath() + "/abs.md")),
                artifact("p", "up", Path.of("x/up.md"), Map.of("location", "../../etc/up.md")),
                artifact("p", "drive", Path.of("x/drive.md"), Map.of("location", "C:/reports/drive.md"))));

        assertThat(content).doesNotContain(temp.toAbsolutePath().toString())
                .doesNotContain("../")
                .doesNotContain("C:/")
                .contains("- **abs** (report): `abs.md`")
                .contains("- **up** (report): `up.md`");
    }

    @Test
    void failingReportsAreListedFirstUnderNeedsAttention(@TempDir Path temp) throws Exception {
        Path runRoot = temp.resolve("run-1");
        String content = summary(runRoot, List.of(
                artifact("module-api-explorer", "report.md", Path.of("build/explorer/report.md"),
                        Map.of("location", "build/explorer/report.md", "healthy", "false")),
                artifact("module-db-contract", "report.md", Path.of("build/db/report.md"),
                        Map.of("compatible", "false")),
                artifact("module-api-fuzz", "demo/run.json", Path.of("build/fuzz/run.json"),
                        Map.of("outcome", "FINDINGS")),
                artifact("module-api-fuzz", "other/run.json", Path.of("build/fuzz/other.json"),
                        Map.of("outcome", "EXECUTION_ERROR")),
                artifact("module-api-discovery", "report.md", Path.of("build/discovery/report.md"),
                        Map.of("healthy", "true"))));

        assertThat(content.indexOf("## Needs attention")).isLessThan(content.indexOf("## Diagnostics by Source"));
        String attention = content.substring(content.indexOf("## Needs attention"),
                content.indexOf("## Diagnostics by Source"));
        assertThat(attention)
                .contains("- **module-api-explorer** report.md: `build/explorer/report.md` (outside the run directory) — healthy: false")
                .contains("- **module-db-contract** report.md: `report.md` — compatible: false")
                .contains("outcome: FINDINGS")
                .contains("outcome: EXECUTION_ERROR")
                .doesNotContain("module-api-discovery");
    }

    @Test
    void aHealthyRunHasNoAttentionSectionAndPlainArtifactsLookAsBefore(@TempDir Path temp) throws Exception {
        Path runRoot = temp.resolve("run-1");
        Path inside = runRoot.resolve("module-mock/scope.json");
        Files.createDirectories(inside.getParent());
        Files.createFile(inside);

        String content = summary(runRoot, List.of(
                artifact("module-mock", "scope.json", inside, Map.of()),
                artifact("module-api-discovery", "report.md", Path.of("build/d/report.md"), Map.of("healthy", "true"))));

        assertThat(content).doesNotContain("Needs attention")
                .contains("- **scope.json** (report): `module-mock/scope.json`\n");
    }

    @Test
    void attentionRulesAreExplicit() {
        assertThat(ArtifactSummaryWriter.needsAttention(artifact("p", "n", Path.of("n"), Map.of("outcome", "PASSED"))))
                .isFalse();
        assertThat(ArtifactSummaryWriter.needsAttention(artifact("p", "n", Path.of("n"), Map.of()))).isFalse();
        assertThat(ArtifactSummaryWriter.needsAttention(artifact("p", "n", Path.of("n"),
                Map.of("outcome", "CONFIGURATION_ERROR")))).isTrue();
    }
}
