package io.testforge.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.testforge.artifact.ArtifactSink;
import io.testforge.artifact.TestArtifact;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactReportingLifecycleTest {

    @Test
    void writesManifestAndSummaryOnStop(@TempDir Path tempDir) {
        ArtifactRunLayout layout = new ArtifactRunLayout(tempDir, "lifecycle-run");
        RunArtifactSink sink = new RunArtifactSink(layout);
        ArtifactManifestWriter manifestWriter = new ArtifactManifestWriter();
        ArtifactSummaryWriter summaryWriter = new ArtifactSummaryWriter();

        sink.write("module-a", "log", "file.txt", "text/plain", "data");

        ArtifactReportingLifecycle lifecycle = new ArtifactReportingLifecycle(
                sink, layout, manifestWriter, summaryWriter
        );

        lifecycle.start();
        assertThat(lifecycle.isRunning()).isTrue();

        lifecycle.stop();
        assertThat(lifecycle.isRunning()).isFalse();

        Path runRoot = layout.getRunRoot();
        assertThat(Files.exists(runRoot.resolve("manifest.json"))).isTrue();
        assertThat(Files.exists(runRoot.resolve("summary.md"))).isTrue();
    }

    @Test
    void isIdempotentAndDoesNotRewriteOnMultipleCalls(@TempDir Path tempDir) {
        ArtifactRunLayout layout = new ArtifactRunLayout(tempDir, "idempotent-run");
        RunArtifactSink sink = new RunArtifactSink(layout);
        ArtifactManifestWriter manifestWriter = new ArtifactManifestWriter();
        ArtifactSummaryWriter summaryWriter = new ArtifactSummaryWriter();

        sink.write("module-a", "log", "file.txt", "text/plain", "data");

        ArtifactReportingLifecycle lifecycle = new ArtifactReportingLifecycle(
                sink, layout, manifestWriter, summaryWriter
        );

        lifecycle.start();
        lifecycle.stop();
        lifecycle.stop();
        lifecycle.destroy();

        Path runRoot = layout.getRunRoot();
        assertThat(Files.exists(runRoot.resolve("manifest.json"))).isTrue();
        assertThat(Files.exists(runRoot.resolve("summary.md"))).isTrue();
    }

    @Test
    void writingFailureIsBestEffortAndDoesNotThrow() {
        ArtifactSink throwingSink = new ArtifactSink() {
            @Override
            public Path directoryFor(String source) {
                throw new RuntimeException("Directory failure");
            }

            @Override
            public void register(TestArtifact artifact) {
                throw new RuntimeException("Register failure");
            }

            @Override
            public TestArtifact write(String source, String category, String name, String mediaType, String content) {
                throw new RuntimeException("Write failure");
            }
        };

        ArtifactManifestWriter throwingManifestWriter = new ArtifactManifestWriter() {
            @Override
            public Optional<Path> write(ArtifactRunLayout layout, List<TestArtifact> artifacts, List<String> reportingProblems) {
                throw new RuntimeException("Manifest write failure");
            }
        };

        ArtifactSummaryWriter throwingSummaryWriter = new ArtifactSummaryWriter() {
            @Override
            public Optional<Path> write(ArtifactRunLayout layout, List<TestArtifact> artifacts, List<String> reportingProblems) {
                throw new RuntimeException("Summary write failure");
            }
        };

        ArtifactRunLayout layout = new ArtifactRunLayout();
        ArtifactReportingLifecycle lifecycle = new ArtifactReportingLifecycle(
                throwingSink, layout, throwingManifestWriter, throwingSummaryWriter
        );

        assertThatCode(() -> {
            lifecycle.start();
            lifecycle.stop();
            lifecycle.destroy();
        }).doesNotThrowAnyException();
    }
}
