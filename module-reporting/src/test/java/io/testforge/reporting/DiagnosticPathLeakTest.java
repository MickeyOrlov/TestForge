package io.testforge.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards against absolute filesystem paths leaking into the manifest through EXCEPTION TEXT.
 *
 * <p>Independent review finding B2-1. The manifest already relativises an artifact's own
 * {@code path}, but exception messages were a second, unguarded channel: Java's filesystem
 * exceptions put the absolute path in {@code getMessage()}, and that text was copied verbatim
 * into {@code metadata["error"]} (RunArtifactSink) and into {@code reportingProblems}
 * (ReportingProblem). A real reproduction produced a manifest containing
 * {@code "/var/folders/.../leak.txt: Not a directory"} — on a developer machine that is
 * {@code /Users/<username>/...}.
 *
 * <p>The pre-existing {@code noAbsolutePathAppearsInManifest} test could never catch this
 * because it passes an empty metadata map, so the error path was never exercised.
 */
class DiagnosticPathLeakTest {

    @Test
    void writeFailureDoesNotLeakAbsolutePathIntoManifest(@TempDir Path tempDir) throws Exception {
        Path base = tempDir.resolve("base");
        Files.createDirectories(base);

        ArtifactRunLayout layout = new ArtifactRunLayout(base, "leak-run");
        RunArtifactSink sink = new RunArtifactSink(layout);

        // Occupy the per-source directory path with a FILE so the write fails with a
        // filesystem exception whose message carries the absolute path.
        Path sourceDir = layout.runRoot().resolve("module-leak");
        Files.createDirectories(sourceDir.getParent());
        Files.createFile(sourceDir);

        sink.write("module-leak", "diag", "leak.txt", "text/plain", "payload");

        new ArtifactManifestWriter().writeManifest(
                layout,
                sink.artifacts(),
                sink.problems().stream().map(p -> p.operation() + ": " + p.message()).toList());

        String content = Files.readString(layout.runRoot().resolve("manifest.json"));

        assertThat(content)
                .as("no absolute path may reach the manifest, including via exception text")
                .doesNotContain(tempDir.toAbsolutePath().normalize().toString());
        assertThat(content)
                .as("the useful part of the failure reason should survive sanitisation")
                .contains("writeFailed");
    }

    @Test
    void reportingProblemSanitisesAbsolutePathsInItsMessage() {
        ReportingProblem p = new ReportingProblem(
                "write", new java.nio.file.FileSystemException("/Users/someone/secret/run/x.txt", null, "Not a directory"));

        assertThat(p.message()).doesNotContain("/Users/someone");
        assertThat(p.message()).contains("Not a directory");
    }

    @Test
    void sanitiserKeepsTheReasonAndDropsOnlyThePath() {
        assertThat(DiagnosticText.sanitise("/Users/alice/build/run/module/x.txt: Not a directory"))
                .isEqualTo("x.txt: Not a directory");
        assertThat(DiagnosticText.sanitise("C:\\Users\\alice\\build\\x.txt: Access denied"))
                .isEqualTo("x.txt: Access denied");
    }

    @Test
    void sanitiserIsNullAndFailureSafe() {
        assertThat(DiagnosticText.sanitise(null)).isEmpty();
        assertThat(DiagnosticText.sanitise("")).isEmpty();
        assertThat(DiagnosticText.sanitise("no paths here at all")).isEqualTo("no paths here at all");
    }
}
