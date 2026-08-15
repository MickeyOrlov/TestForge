package io.testforge.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testforge.artifact.TestArtifact;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunArtifactSinkTest {

    @Test
    void directoryForDelegatesToLayout(@TempDir Path tempDir) {
        ArtifactRunLayout layout = new ArtifactRunLayout(tempDir);
        RunArtifactSink sink = new RunArtifactSink(layout);

        Path dir = sink.directoryFor("module-flow");

        assertThat(dir).isNotNull();
        assertThat(dir).isEqualTo(layout.directoryFor("module-flow"));
        assertThat(Files.isDirectory(dir)).isTrue();
    }

    @Test
    void registrationOrderingIsDeterministic(@TempDir Path tempDir) {
        ArtifactRunLayout layout = new ArtifactRunLayout(tempDir);
        RunArtifactSink sink = new RunArtifactSink(layout);

        Instant t0 = Instant.parse("2026-08-15T10:00:00Z");
        Instant t1 = Instant.parse("2026-08-15T11:00:00Z");

        TestArtifact a1 = new TestArtifact("source-b", "cat-1", "name-1", tempDir.resolve("f1"), "text/plain", t1, Map.of());
        TestArtifact a2 = new TestArtifact("source-a", "cat-1", "name-1", tempDir.resolve("f2"), "text/plain", t1, Map.of());
        TestArtifact a3 = new TestArtifact("source-z", "cat-1", "name-1", tempDir.resolve("f3"), "text/plain", t0, Map.of());
        TestArtifact a4 = new TestArtifact("source-a", "cat-0", "name-1", tempDir.resolve("f4"), "text/plain", t1, Map.of());
        TestArtifact a5 = new TestArtifact("source-a", "cat-0", "name-0", tempDir.resolve("f5"), "text/plain", t1, Map.of());

        // Register out of order
        sink.register(a1);
        sink.register(a2);
        sink.register(a3);
        sink.register(a4);
        sink.register(a5);

        List<TestArtifact> snapshot = sink.artifacts();

        // Expected order:
        // 1. a3 (createdAt t0)
        // 2. a5 (createdAt t1, source-a, cat-0, name-0)
        // 3. a4 (createdAt t1, source-a, cat-0, name-1)
        // 4. a2 (createdAt t1, source-a, cat-1, name-1)
        // 5. a1 (createdAt t1, source-b, cat-1, name-1)
        assertThat(snapshot).containsExactly(a3, a5, a4, a2, a1);

        assertThatThrownBy(() -> snapshot.add(a1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void writeCreatesReadableFileAndRegistersIt(@TempDir Path tempDir) throws IOException {
        ArtifactRunLayout layout = new ArtifactRunLayout(tempDir);
        RunArtifactSink sink = new RunArtifactSink(layout);

        String content = "{\"status\":\"UP\",\"cpu\":12.5}";
        TestArtifact artifact = sink.write(
                "module-reporting",
                "resource-usage",
                "metrics.json",
                "application/json",
                content
        );

        assertThat(artifact).isNotNull();
        assertThat(artifact.source()).isEqualTo("module-reporting");
        assertThat(artifact.category()).isEqualTo("resource-usage");
        assertThat(artifact.name()).isEqualTo("metrics.json");
        assertThat(artifact.mediaType()).isEqualTo("application/json");

        Path writtenFile = artifact.file();
        assertThat(writtenFile).isNotNull();
        assertThat(Files.exists(writtenFile)).isTrue();
        assertThat(Files.readString(writtenFile, StandardCharsets.UTF_8)).isEqualTo(content);

        assertThat(sink.artifacts()).contains(artifact);
        assertThat(sink.problems()).isEmpty();
    }

    @Test
    void writeIntoUnwritableLocationDoesNotThrowReturnsArtifactAndRecordsProblem(@TempDir Path tempDir) throws IOException {
        ArtifactRunLayout layout = new ArtifactRunLayout(tempDir);
        RunArtifactSink sink = new RunArtifactSink(layout);

        // Block the source directory path by placing a file where the directory would be created
        String blockedSource = "blocked-source";
        Path blockingFile = layout.getRunRoot().resolve(blockedSource);
        Files.createFile(blockingFile);

        assertThatCode(() -> {
            TestArtifact result = sink.write(
                    blockedSource,
                    "diagnostics",
                    "failure.log",
                    "text/plain",
                    "Unwritten log content"
            );

            assertThat(result).isNotNull();
            assertThat(result.source()).isEqualTo(blockedSource);
            assertThat(result.category()).isEqualTo("diagnostics");
            assertThat(result.name()).isEqualTo("failure.log");
            assertThat(result.metadata()).containsEntry("writeFailed", "true");

            List<ReportingProblem> problems = sink.problems();
            assertThat(problems).isNotEmpty();
            ReportingProblem problem = problems.get(0);
            assertThat(problem.operation()).isEqualTo("write");
            assertThat(problem.cause()).isNotNull();
        }).doesNotThrowAnyException();
    }

    @Test
    void concurrentRegisterAndWriteLosesNothingAndProducesDistinctFiles(@TempDir Path tempDir) throws Exception {
        ArtifactRunLayout layout = new ArtifactRunLayout(tempDir);
        RunArtifactSink sink = new RunArtifactSink(layout);

        int registerCount = 25;
        int writeCount = 25;
        int totalTasks = registerCount + writeCount;

        ExecutorService executor = Executors.newFixedThreadPool(16);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < registerCount; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                TestArtifact artifact = new TestArtifact(
                        "concurrent-reg",
                        "test-cat",
                        "reg-" + index + ".txt",
                        tempDir.resolve("reg-" + index + ".txt"),
                        "text/plain",
                        Instant.now(),
                        Map.of()
                );
                sink.register(artifact);
            }));
        }

        for (int i = 0; i < writeCount; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                sink.write(
                        "concurrent-write",
                        "test-cat",
                        "output.log",
                        "text/plain",
                        "Thread payload " + index
                );
            }));
        }

        executor.shutdown();
        boolean finished = executor.awaitTermination(10, TimeUnit.SECONDS);
        assertThat(finished).isTrue();

        for (Future<?> future : futures) {
            future.get();
        }

        List<TestArtifact> allArtifacts = sink.artifacts();
        assertThat(allArtifacts).hasSize(totalTasks);

        Set<Path> writtenPaths = ConcurrentHashMap.newKeySet();
        for (TestArtifact artifact : allArtifacts) {
            assertThat(artifact).isNotNull();
            if ("concurrent-write".equals(artifact.source())) {
                assertThat(Files.exists(artifact.file())).isTrue();
                boolean pathIsUnique = writtenPaths.add(artifact.file());
                assertThat(pathIsUnique).as("Duplicate file path detected: %s", artifact.file()).isTrue();
            }
        }

        assertThat(writtenPaths).hasSize(writeCount);
        assertThat(sink.problems()).isEmpty();
    }

    @Test
    void methodsAreRobustAgainstNullAndBlankInputs() {
        RunArtifactSink sink = new RunArtifactSink(null);

        assertThatCode(() -> {
            Path dir = sink.directoryFor(null);
            assertThat(dir).isNotNull();

            sink.register(null);

            TestArtifact result = sink.write(null, null, null, null, null);
            assertThat(result).isNotNull();
            assertThat(result.file()).isNotNull();

            assertThat(sink.artifacts()).isNotEmpty();
            assertThat(sink.problems()).isNotEmpty();
        }).doesNotThrowAnyException();
    }
}
