package io.testforge.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.testforge.artifact.ArtifactSink;
import io.testforge.artifact.TestArtifact;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceUsageMonitorTest {

    @TempDir
    Path tempDir;

    @Test
    void stop_withRecordingSink_publishesResourceUsageArtifact() throws Exception {
        RecordingArtifactSink recordingSink = new RecordingArtifactSink(tempDir);
        ResourceUsageMonitor monitor = new ResourceUsageMonitor(recordingSink);

        monitor.start(Duration.ofMillis(10));

        long deadline = System.currentTimeMillis() + 3000;
        while (monitor.stats().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        Optional<ResourceUsageStats> stats = monitor.stop();

        assertThat(stats).isPresent();
        assertThat(monitor.isRunning()).isFalse();

        List<TestArtifact> recorded = recordingSink.writtenArtifacts();
        assertThat(recorded).hasSize(1);

        TestArtifact artifact = recorded.getFirst();
        assertThat(artifact.source()).isEqualTo("module-reporting");
        assertThat(artifact.category()).isEqualTo("resource-usage");
        assertThat(artifact.name()).isEqualTo("resource-usage.txt");
        assertThat(artifact.mediaType()).isEqualTo("text/plain");

        String fileContent = Files.readString(artifact.file());
        assertThat(fileContent).contains("samples:");
        assertThat(fileContent).contains("heap used, MB:");
        assertThat(fileContent).contains("process CPU:");
        assertThat(fileContent).contains("system CPU:");
    }

    @Test
    void stop_withNoOpSink_nothingWrittenAndNothingThrows() throws Exception {
        ResourceUsageMonitor monitor = new ResourceUsageMonitor(ArtifactSink.NO_OP);

        monitor.start(Duration.ofMillis(10));

        long deadline = System.currentTimeMillis() + 3000;
        while (monitor.stats().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        assertThatCode(() -> {
            Optional<ResourceUsageStats> stats = monitor.stop();
            assertThat(stats).isPresent();
        }).doesNotThrowAnyException();

        assertThat(monitor.isRunning()).isFalse();
    }

    @Test
    void stop_withThrowingSink_doesNotBreakMonitorLifecycle() throws Exception {
        ArtifactSink throwingSink = new ArtifactSink() {
            @Override
            public Path directoryFor(String source) {
                throw new RuntimeException("Simulated directoryFor failure");
            }

            @Override
            public void register(TestArtifact artifact) {
                throw new RuntimeException("Simulated register failure");
            }

            @Override
            public TestArtifact write(String source, String category, String name, String mediaType, String content) {
                throw new RuntimeException("Simulated write failure");
            }
        };

        ResourceUsageMonitor monitor = new ResourceUsageMonitor(throwingSink);

        monitor.start(Duration.ofMillis(10));

        long deadline = System.currentTimeMillis() + 3000;
        while (monitor.stats().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        assertThatCode(() -> {
            Optional<ResourceUsageStats> stats = monitor.stop();
            assertThat(stats).isPresent();
        }).doesNotThrowAnyException();

        assertThat(monitor.isRunning()).isFalse();
    }

    @Test
    void stop_whenNoSamples_publishesNothing() {
        RecordingArtifactSink recordingSink = new RecordingArtifactSink(tempDir);
        ResourceUsageMonitor monitor = new ResourceUsageMonitor(recordingSink);

        Optional<ResourceUsageStats> stats = monitor.stop();

        assertThat(stats).isEmpty();
        assertThat(recordingSink.writtenArtifacts()).isEmpty();
    }

    @Test
    void nullSink_defaultsToNoOp() {
        ResourceUsageMonitor monitor = new ResourceUsageMonitor(null);
        assertThatCode(() -> monitor.start(Duration.ofMillis(100))).doesNotThrowAnyException();
        monitor.stop();
    }

    private static class RecordingArtifactSink implements ArtifactSink {
        private final Path baseDir;
        private final List<TestArtifact> writtenArtifacts = new ArrayList<>();

        RecordingArtifactSink(Path baseDir) {
            this.baseDir = baseDir;
        }

        @Override
        public Path directoryFor(String source) {
            return baseDir.resolve(source != null ? source : "unknown");
        }

        @Override
        public void register(TestArtifact artifact) {
            if (artifact != null) {
                writtenArtifacts.add(artifact);
            }
        }

        @Override
        public TestArtifact write(String source, String category, String name, String mediaType, String content) {
            try {
                Path dir = directoryFor(source);
                Files.createDirectories(dir);
                Path file = dir.resolve(name != null ? name : "artifact.tmp");
                Files.writeString(file, content != null ? content : "");
                TestArtifact artifact = new TestArtifact(
                        source, category, name, file, mediaType, Instant.now(), Map.of());
                register(artifact);
                return artifact;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        List<TestArtifact> writtenArtifacts() {
            return List.copyOf(writtenArtifacts);
        }
    }
}
