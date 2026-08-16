package io.testforge.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactRunLayoutTest {

    @Test
    void runRootCreatedUnderConfiguredBase(@TempDir Path tempDir) {
        Path customBase = tempDir.resolve("custom-artifacts");
        ArtifactRunLayout layout = new ArtifactRunLayout(customBase);

        Path runRoot = layout.runRoot();

        assertThat(runRoot).isNotNull();
        assertThat(runRoot.toAbsolutePath().normalize())
                .startsWith(customBase.toAbsolutePath().normalize());
        assertThat(Files.exists(runRoot)).isTrue();
        assertThat(Files.isDirectory(runRoot)).isTrue();
    }

    @Test
    void explicitRunIdIsHonored(@TempDir Path tempDir) {
        String explicitRunId = "pinned-ci-run-12345";
        ArtifactRunLayout layout = new ArtifactRunLayout(tempDir, explicitRunId);

        assertThat(layout.runId()).isEqualTo(explicitRunId);
        assertThat(layout.runRoot()).isEqualTo(tempDir.resolve(explicitRunId));
        assertThat(Files.exists(layout.runRoot())).isTrue();
    }

    @Test
    void twoRunsGetDifferentDirectories(@TempDir Path tempDir) {
        ArtifactRunLayout run1 = new ArtifactRunLayout(tempDir);
        ArtifactRunLayout run2 = new ArtifactRunLayout(tempDir);

        assertThat(run1.runId()).isNotEqualTo(run2.runId());
        assertThat(run1.runRoot()).isNotEqualTo(run2.runRoot());
        assertThat(Files.exists(run1.runRoot())).isTrue();
        assertThat(Files.exists(run2.runRoot())).isTrue();
    }

    @Test
    void directoryForSanitisesHostileSource(@TempDir Path tempDir) {
        ArtifactRunLayout layout = new ArtifactRunLayout(tempDir);
        Path runRoot = layout.runRoot().toAbsolutePath().normalize();

        Path hostileDir1 = layout.directoryFor("../evil");
        Path hostileDir2 = layout.directoryFor("../../etc/passwd");
        Path hostileDir3 = layout.directoryFor("foo/bar/baz");

        assertThat(hostileDir1.toAbsolutePath().normalize()).startsWith(runRoot);
        assertThat(hostileDir2.toAbsolutePath().normalize()).startsWith(runRoot);
        assertThat(hostileDir3.toAbsolutePath().normalize()).startsWith(runRoot);

        assertThat(Files.exists(hostileDir1)).isTrue();
        assertThat(Files.exists(hostileDir2)).isTrue();
        assertThat(Files.exists(hostileDir3)).isTrue();
    }

    @Test
    void concurrentRequestsForSameLogicalNameProduceDistinctPaths(@TempDir Path tempDir) throws Exception {
        ArtifactRunLayout layout = new ArtifactRunLayout(tempDir);
        String source = "flow";
        String logicalName = "diagnostics.json";
        int numThreads = 20;

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        List<Future<Path>> futures = new ArrayList<>();

        for (int i = 0; i < numThreads; i++) {
            futures.add(executor.submit(() -> layout.resolveUniqueFile(source, logicalName)));
        }

        executor.shutdown();
        boolean finished = executor.awaitTermination(10, TimeUnit.SECONDS);
        assertThat(finished).isTrue();

        Set<Path> allocatedPaths = ConcurrentHashMap.newKeySet();
        for (Future<Path> future : futures) {
            Path allocatedPath = future.get();
            assertThat(allocatedPath).isNotNull();
            assertThat(Files.exists(allocatedPath)).isTrue();
            boolean newlyAdded = allocatedPaths.add(allocatedPath);
            assertThat(newlyAdded).as("Path %s was duplicated!", allocatedPath).isTrue();
        }

        assertThat(allocatedPaths).hasSize(numThreads);
    }

    @Test
    void relativizeReturnsRelativePathInsideRunRootAndDoesNotThrowOutside(@TempDir Path tempDir) throws IOException {
        ArtifactRunLayout layout = new ArtifactRunLayout(tempDir);
        Path runRoot = layout.runRoot();

        Path sourceDir = layout.directoryFor("module-http");
        Path fileInside = layout.resolveUniqueFile(sourceDir, "request.log");

        Path relativePath = layout.relativize(fileInside);

        assertThat(relativePath.isAbsolute()).isFalse();
        assertThat(relativePath.toString()).isEqualTo("module-http/request.log");

        Path fileOutside = tempDir.resolve("outside-directory/some-external-file.txt");
        Files.createDirectories(fileOutside.getParent());
        Files.createFile(fileOutside);

        assertThatCode(() -> {
            Path relativeFallback = layout.relativize(fileOutside);
            assertThat(relativeFallback).isNotNull();
            assertThat(relativeFallback.isAbsolute()).isFalse();
            assertThat(relativeFallback.toString()).isEqualTo("some-external-file.txt");
        }).doesNotThrowAnyException();
    }

    @Test
    void relativizeHandlesEdgeCasesGracefully(@TempDir Path tempDir) {
        ArtifactRunLayout layout = new ArtifactRunLayout(tempDir);

        assertThat(layout.relativize((Path) null)).isEqualTo(Paths.get(""));
        assertThat(layout.relativize((String) null)).isEqualTo(Paths.get(""));

        Path runRoot = layout.runRoot();
        assertThat(layout.relativize(runRoot)).isEqualTo(Paths.get(""));
    }

    @Test
    void bestEffortHandlesCreationFailuresWithoutThrowing() {
        Path tempFile;
        try {
            tempFile = Files.createTempFile("testforge-layout-test", ".tmp");
            tempFile.toFile().deleteOnExit();
        } catch (IOException e) {
            return;
        }

        assertThatCode(() -> {
            ArtifactRunLayout layout = new ArtifactRunLayout(tempFile);
            assertThat(layout.runRoot()).isNotNull();

            Path subDir = layout.directoryFor("source");
            assertThat(subDir).isNotNull();

            Path file = layout.resolveUniqueFile("source", "file.log");
            assertThat(file).isNotNull();
        }).doesNotThrowAnyException();

        // Not-null and no-throw are not enough: Review B (B1-7) showed this test
        // would still pass if the tmpdir fallback were replaced by a garbage path,
        // because every accessor merely logs and returns something non-null. Assert
        // the fallback actually produced a USABLE directory.
        ArtifactRunLayout layout = new ArtifactRunLayout(tempFile);
        assertThat(layout.runRoot())
                .as("failed run root must fall back under java.io.tmpdir")
                .startsWith(Path.of(System.getProperty("java.io.tmpdir")));
        assertThat(Files.isDirectory(layout.runRoot()))
                .as("fallback run root must be a real, writable directory")
                .isTrue();
        assertThat(Files.isDirectory(layout.directoryFor("source")))
                .as("fallback source directory must actually exist")
                .isTrue();
    }

    @Test
    void resolveUniqueFileRefusesADirectoryOutsideTheRunRoot(@TempDir Path baseDir) throws IOException {
        // Review B (B1-1): the public (Path, String) overload took the caller's
        // directory as-is, so it would create and write into e.g. /tmp/evil.
        ArtifactRunLayout layout = new ArtifactRunLayout(baseDir);
        Path outside = Files.createTempDirectory("testforge-outside");
        outside.toFile().deleteOnExit();

        Path resolved = layout.resolveUniqueFile(outside, "payload.txt");

        assertThat(resolved)
                .as("an out-of-root directory must be redirected into the run root")
                .startsWith(layout.runRoot().toAbsolutePath().normalize());
        assertThat(Files.exists(outside.resolve("payload.txt")))
                .as("nothing may be written outside the run root")
                .isFalse();
    }
}
