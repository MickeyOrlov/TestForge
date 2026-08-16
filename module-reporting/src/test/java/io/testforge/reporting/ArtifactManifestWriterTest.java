package io.testforge.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.artifact.TestArtifact;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactManifestWriterTest {

    private ArtifactManifestWriter writer;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        writer = new ArtifactManifestWriter();
        mapper = ArtifactManifestWriter.createDefaultObjectMapper();
    }

    @Test
    void manifestJsonIsValidAndContainsExpectedArtifacts(@TempDir Path tempDir) throws IOException {
        Path runRoot = tempDir.resolve("run-100");
        Files.createDirectories(runRoot);
        String runId = "run-100";

        Path file1 = runRoot.resolve("module-flow/trace.json");
        Path file2 = runRoot.resolve("module-http/request.log");
        Files.createDirectories(file1.getParent());
        Files.createDirectories(file2.getParent());
        Files.createFile(file1);
        Files.createFile(file2);

        Instant now = Instant.now();
        TestArtifact artifact1 = new TestArtifact(
                "module-flow", "flow-path", "trace", file1, "application/json", now.minusSeconds(10), Map.of("step", "1")
        );
        TestArtifact artifact2 = new TestArtifact(
                "module-http", "http-log", "req-1", file2, "text/plain", now, Map.of("status", "200")
        );

        List<TestArtifact> artifacts = List.of(artifact1, artifact2);
        List<String> problems = List.of("Failed to record metric");

        boolean result = writer.writeManifest(runRoot, runId, artifacts, problems);
        assertThat(result).isTrue();

        Path manifestPath = runRoot.resolve("manifest.json");
        assertThat(Files.exists(manifestPath)).isTrue();

        JsonNode rootNode = mapper.readTree(manifestPath.toFile());
        assertThat(rootNode.get("runId").asText()).isEqualTo("run-100");
        assertThat(rootNode.get("artifactCount").asInt()).isEqualTo(2);

        JsonNode problemsNode = rootNode.get("reportingProblems");
        assertThat(problemsNode.isArray()).isTrue();
        assertThat(problemsNode.get(0).asText()).isEqualTo("Failed to record metric");

        JsonNode artifactsNode = rootNode.get("artifacts");
        assertThat(artifactsNode.isArray()).isTrue();
        assertThat(artifactsNode.size()).isEqualTo(2);

        JsonNode node0 = artifactsNode.get(0);
        assertThat(node0.get("source").asText()).isEqualTo("module-flow");
        assertThat(node0.get("category").asText()).isEqualTo("flow-path");
        assertThat(node0.get("name").asText()).isEqualTo("trace");
        assertThat(node0.get("path").asText()).isEqualTo("module-flow/trace.json");
        assertThat(node0.get("file").asText()).isEqualTo("module-flow/trace.json");
        assertThat(node0.get("metadata").get("step").asText()).isEqualTo("1");
    }

    @Test
    void orderingIsIdenticalAcrossTwoRunsWithShuffledInput(@TempDir Path tempDir) throws IOException {
        Path run1 = tempDir.resolve("run-1");
        Path run2 = tempDir.resolve("run-2");
        Files.createDirectories(run1);
        Files.createDirectories(run2);

        Instant t1 = Instant.parse("2026-08-15T10:00:00Z");
        Instant t2 = Instant.parse("2026-08-15T11:00:00Z");

        TestArtifact a1 = new TestArtifact("module-flow", "flow-path", "a-trace", run1.resolve("f1"), "text/plain", t1, Map.of());
        TestArtifact a2 = new TestArtifact("module-flow", "flow-path", "b-trace", run1.resolve("f2"), "text/plain", t1, Map.of());
        TestArtifact a3 = new TestArtifact("module-http", "http-log", "log-1", run1.resolve("f3"), "text/plain", t1, Map.of());
        TestArtifact a4 = new TestArtifact("module-flow", "flow-path", "c-trace", run1.resolve("f4"), "text/plain", t2, Map.of());

        List<TestArtifact> originalList = List.of(a4, a2, a1, a3);
        List<TestArtifact> shuffledList = new ArrayList<>(originalList);
        Collections.shuffle(shuffledList);

        writer.writeManifest(run1, "fixed-run-id", originalList, List.of("problem-1"));

        // For run2, reconstruct TestArtifacts pointing inside run2 to preserve relative path matching
        List<TestArtifact> run2Artifacts = shuffledList.stream()
                .map(a -> new TestArtifact(a.source(), a.category(), a.name(), run2.resolve(a.file().getFileName()), a.mediaType(), a.createdAt(), a.metadata()))
                .toList();
        writer.writeManifest(run2, "fixed-run-id", run2Artifacts, List.of("problem-1"));

        String json1 = Files.readString(run1.resolve("manifest.json"));
        String json2 = Files.readString(run2.resolve("manifest.json"));

        assertThat(json1).isEqualTo(json2);
    }

    @Test
    void noAbsolutePathAppearsInManifest(@TempDir Path tempDir) throws IOException {
        Path runRoot = tempDir.resolve("run-sec");
        Files.createDirectories(runRoot);

        Path insideFile = runRoot.resolve("sub/inside.log");
        Path outsideFile = tempDir.resolve("outside/external.log");
        Files.createDirectories(insideFile.getParent());
        Files.createDirectories(outsideFile.getParent());
        Files.createFile(insideFile);
        Files.createFile(outsideFile);

        TestArtifact aInside = new TestArtifact("module-a", "cat-a", "inside", insideFile, "text/plain", Instant.now(), Map.of());
        TestArtifact aOutside = new TestArtifact("module-b", "cat-b", "outside", outsideFile, "text/plain", Instant.now(), Map.of());

        writer.writeManifest(runRoot, "run-sec", List.of(aInside, aOutside), List.of());

        Path manifestPath = runRoot.resolve("manifest.json");
        String content = Files.readString(manifestPath);

        // Assert the run root's own absolute string is completely absent
        String absoluteRunRootString = runRoot.toAbsolutePath().normalize().toString();
        assertThat(content).doesNotContain(absoluteRunRootString);

        // Assert tempDir absolute string is absent
        String absoluteTempDirString = tempDir.toAbsolutePath().normalize().toString();
        assertThat(content).doesNotContain(absoluteTempDirString);

        // Assert relative paths are formatted as simple relative strings
        JsonNode rootNode = mapper.readTree(content);
        JsonNode artifacts = rootNode.get("artifacts");
        for (JsonNode artifact : artifacts) {
            String path = artifact.get("path").asText();
            assertThat(Paths.get(path).isAbsolute()).isFalse();
            assertThat(path).doesNotContain(":");
        }
    }

    @Test
    void reportingProblemsAppearInManifest(@TempDir Path tempDir) throws IOException {
        Path runRoot = tempDir.resolve("run-probs");
        Files.createDirectories(runRoot);
        List<String> problems = List.of("Error 1: timeout", "Error 2: disc full");

        writer.writeManifest(runRoot, "run-probs", List.of(), problems);

        JsonNode rootNode = mapper.readTree(runRoot.resolve("manifest.json").toFile());
        JsonNode problemsNode = rootNode.get("reportingProblems");
        assertThat(problemsNode).isNotNull();
        assertThat(problemsNode.size()).isEqualTo(2);
        assertThat(problemsNode.get(0).asText()).isEqualTo("Error 1: timeout");
        assertThat(problemsNode.get(1).asText()).isEqualTo("Error 2: disc full");
    }

    @Test
    void unwritableTargetDoesNotThrow(@TempDir Path tempDir) throws IOException {
        // Create a regular file where the runRoot directory would be, making runRoot unwritable
        Path blockingFile = tempDir.resolve("unwritable-root");
        Files.createFile(blockingFile);

        Path invalidRunRoot = blockingFile.resolve("nested");

        assertThatCode(() -> {
            boolean result = writer.writeManifest(invalidRunRoot, "run-err", List.of(), List.of());
            assertThat(result).isFalse();

            Optional<Path> opt = writer.write(invalidRunRoot, "run-err", List.of(), List.of());
            assertThat(opt).isEmpty();
        }).doesNotThrowAnyException();
    }
}
