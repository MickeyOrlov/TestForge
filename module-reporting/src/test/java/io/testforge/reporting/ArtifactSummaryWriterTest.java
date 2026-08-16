package io.testforge.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.testforge.artifact.TestArtifact;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactSummaryWriterTest {

    private ArtifactSummaryWriter writer;

    @BeforeEach
    void setUp() {
        writer = new ArtifactSummaryWriter();
    }

    @Test
    void summaryMdIsGeneratedWithGroupedSourcesAndProblems(@TempDir Path tempDir) throws IOException {
        Path runRoot = tempDir.resolve("run-summary");
        Files.createDirectories(runRoot);
        String runId = "run-summary";

        Path f1 = runRoot.resolve("module-flow/trace.json");
        Path f2 = runRoot.resolve("module-http/req.log");
        Path f3 = runRoot.resolve("module-flow/state.json");
        Files.createDirectories(f1.getParent());
        Files.createDirectories(f2.getParent());
        Files.createFile(f1);
        Files.createFile(f2);
        Files.createFile(f3);

        Instant now = Instant.now();
        TestArtifact a1 = new TestArtifact("module-flow", "flow-path", "trace", f1, "application/json", now, Map.of());
        TestArtifact a2 = new TestArtifact("module-http", "http-log", "req", f2, "text/plain", now, Map.of());
        TestArtifact a3 = new TestArtifact("module-flow", "state-dump", "state", f3, "application/json", now, Map.of());

        List<TestArtifact> artifacts = List.of(a1, a2, a3);
        List<String> problems = List.of("Warn 1: Playwright warning", "Warn 2: Kafka delay");

        boolean result = writer.writeSummary(runRoot, runId, artifacts, problems);
        assertThat(result).isTrue();

        Path summaryPath = runRoot.resolve("summary.md");
        assertThat(Files.exists(summaryPath)).isTrue();

        String content = Files.readString(summaryPath);
        assertThat(content).contains("# Test Run Summary: run-summary");
        assertThat(content).contains("**Artifact Count:** 3");

        // Group headers
        assertThat(content).contains("### module-flow");
        assertThat(content).contains("### module-http");

        // Artifact items with relative paths
        assertThat(content).contains("- **trace** (flow-path): `module-flow/trace.json`");
        assertThat(content).contains("- **state** (state-dump): `module-flow/state.json`");
        assertThat(content).contains("- **req** (http-log): `module-http/req.log`");

        // Reporting problems section
        assertThat(content).contains("## Reporting Problems");
        assertThat(content).contains("- Warn 1: Playwright warning");
        assertThat(content).contains("- Warn 2: Kafka delay");
    }

    @Test
    void summaryOmitsReportingProblemsSectionWhenNoneExist(@TempDir Path tempDir) throws IOException {
        Path runRoot = tempDir.resolve("run-noprobs");
        Files.createDirectories(runRoot);

        Path f1 = runRoot.resolve("module-flow/trace.json");
        Files.createDirectories(f1.getParent());
        Files.createFile(f1);

        TestArtifact a1 = new TestArtifact("module-flow", "flow-path", "trace", f1, "application/json", Instant.now(), Map.of());

        writer.writeSummary(runRoot, "run-noprobs", List.of(a1), List.of());

        String content = Files.readString(runRoot.resolve("summary.md"));
        assertThat(content).doesNotContain("## Reporting Problems");
    }

    @Test
    void unwritableTargetDoesNotThrow(@TempDir Path tempDir) throws IOException {
        Path blockingFile = tempDir.resolve("unwritable-summary-root");
        Files.createFile(blockingFile);

        Path invalidRunRoot = blockingFile.resolve("nested");

        assertThatCode(() -> {
            boolean result = writer.writeSummary(invalidRunRoot, "run-err", List.of(), List.of());
            assertThat(result).isFalse();

            Optional<Path> opt = writer.write(invalidRunRoot, "run-err", List.of(), List.of());
            assertThat(opt).isEmpty();
        }).doesNotThrowAnyException();
    }
}
