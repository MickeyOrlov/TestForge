package io.testforge.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.artifact.TestArtifact;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests proving Property 2: "PARALLEL SAFETY."
 *
 * <p>Multiple concurrent producers publishing artifacts simultaneously (including using the same source
 * and logical name) must lose no artifacts, cause no path collisions, produce readable files,
 * register all artifacts in manifest.json, and yield deterministic manifest ordering from shuffled inputs.
 */
class ReportingParallelSafetyTest {

    @Test
    void concurrentProducersPublishWithoutArtifactLossOrPathCollisionAndProduceDeterministicManifest(@TempDir Path tempDir) throws Exception {
        ArtifactRunLayout layout = new ArtifactRunLayout(tempDir, "parallel-run");
        RunArtifactSink sink = new RunArtifactSink(layout);

        int numThreads = 16;
        int callsPerThread = 20;
        int totalExpectedArtifacts = numThreads * callsPerThread;

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        List<Future<?>> futures = new ArrayList<>();

        // Concurrently publish artifacts, explicitly including several using the SAME source and SAME logical name
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            futures.add(executor.submit(() -> {
                for (int i = 0; i < callsPerThread; i++) {
                    if (i % 2 == 0) {
                        // SAME source and SAME logical name published by multiple concurrent threads
                        sink.write(
                                "module-web",
                                "screenshot",
                                "failure.png",
                                "image/png",
                                "PNG content from thread " + threadId + " call " + i
                        );
                    } else if (i % 3 == 0) {
                        // Another shared source and logical name
                        sink.write(
                                "module-mock",
                                "mock-log",
                                "unmatched-request.json",
                                "application/json",
                                "{\"thread\":" + threadId + ",\"iter\":" + i + "}"
                        );
                    } else {
                        // Thread-unique name
                        sink.write(
                                "module-http",
                                "http-trace",
                                "trace-" + threadId + "-" + i + ".txt",
                                "text/plain",
                                "Trace log content"
                        );
                    }
                }
            }));
        }

        executor.shutdown();
        boolean terminated = executor.awaitTermination(15, TimeUnit.SECONDS);
        assertThat(terminated).as("All concurrent publishing tasks completed within timeout").isTrue();

        for (Future<?> future : futures) {
            future.get();
        }

        // 1. Assert no artifact is lost
        List<TestArtifact> registeredArtifacts = sink.artifacts();
        assertThat(registeredArtifacts).hasSize(totalExpectedArtifacts);

        // 2. Assert every file exists and is readable, and no two artifacts share a path
        Set<Path> uniquePaths = ConcurrentHashMap.newKeySet();
        for (TestArtifact artifact : registeredArtifacts) {
            Path file = artifact.file();
            assertThat(file).isNotNull();
            assertThat(Files.exists(file)).as("File exists: %s", file).isTrue();
            assertThat(Files.isReadable(file)).as("File is readable: %s", file).isTrue();
            assertThat(Files.size(file)).as("File is non-empty: %s", file).isGreaterThan(0);

            boolean isUnique = uniquePaths.add(file.toAbsolutePath().normalize());
            assertThat(isUnique).as("No two artifacts share a path: duplicate found at %s", file).isTrue();
        }
        assertThat(uniquePaths).hasSize(totalExpectedArtifacts);

        // 3. Assert the manifest built afterwards lists every one of them
        ArtifactManifestWriter manifestWriter = new ArtifactManifestWriter();
        List<String> problems = sink.problems().stream().map(ReportingProblem::message).toList();

        Path manifestFile1 = layout.runRoot().resolve("manifest.json");
        boolean manifestWritten = manifestWriter.writeManifest(layout, registeredArtifacts, problems);
        assertThat(manifestWritten).isTrue();
        assertThat(Files.exists(manifestFile1)).isTrue();

        ObjectMapper mapper = ArtifactManifestWriter.createDefaultObjectMapper();
        JsonNode manifestNode = mapper.readTree(manifestFile1.toFile());

        assertThat(manifestNode.get("artifactCount").asInt()).isEqualTo(totalExpectedArtifacts);
        JsonNode artifactsArray = manifestNode.get("artifacts");
        assertThat(artifactsArray.size()).isEqualTo(totalExpectedArtifacts);

        Set<String> manifestPaths = ConcurrentHashMap.newKeySet();
        for (JsonNode node : artifactsArray) {
            String pathStr = node.get("path").asText();
            assertThat(pathStr).isNotBlank();
            manifestPaths.add(pathStr);
        }
        assertThat(manifestPaths).hasSize(totalExpectedArtifacts);

        // 4. Assert manifest ordering is deterministic across repeated builds from shuffled input
        Path runRootA = tempDir.resolve("manifest-run-A");
        Path runRootB = tempDir.resolve("manifest-run-B");
        Files.createDirectories(runRootA);
        Files.createDirectories(runRootB);

        List<TestArtifact> shuffledInput1 = new ArrayList<>(registeredArtifacts);
        List<TestArtifact> shuffledInput2 = new ArrayList<>(registeredArtifacts);
        Collections.shuffle(shuffledInput1);
        Collections.shuffle(shuffledInput2);

        List<TestArtifact> artifactsForA = shuffledInput1.stream()
                .map(a -> new TestArtifact(a.source(), a.category(), a.name(), runRootA.resolve(layout.relativize(a.file())), a.mediaType(), a.createdAt(), a.metadata()))
                .toList();

        List<TestArtifact> artifactsForB = shuffledInput2.stream()
                .map(a -> new TestArtifact(a.source(), a.category(), a.name(), runRootB.resolve(layout.relativize(a.file())), a.mediaType(), a.createdAt(), a.metadata()))
                .toList();

        manifestWriter.writeManifest(runRootA, "fixed-run-id", artifactsForA, problems);
        manifestWriter.writeManifest(runRootB, "fixed-run-id", artifactsForB, problems);

        String jsonA = Files.readString(runRootA.resolve("manifest.json"));
        String jsonB = Files.readString(runRootB.resolve("manifest.json"));

        assertThat(jsonA).isEqualTo(jsonB);
    }
}
