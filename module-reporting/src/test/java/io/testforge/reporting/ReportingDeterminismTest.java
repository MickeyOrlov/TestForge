package io.testforge.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.artifact.TestArtifact;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests proving Property 3: "DETERMINISM FOR CI."
 *
 * <p>The manifest must contain no absolute paths (never leaking local workspace/user details into CI),
 * and building the manifest twice from the same artifacts must yield byte-identical output.
 */
class ReportingDeterminismTest {

    @Test
    void manifestContainsNoAbsolutePath(@TempDir Path tempDir) throws IOException {
        Path runRoot = tempDir.resolve("ci-run-1");
        Files.createDirectories(runRoot);

        Path insideFile1 = runRoot.resolve("module-flow/trace.txt");
        Path insideFile2 = runRoot.resolve("module-web/screenshot.png");
        Path outsideFile = tempDir.resolve("external/outside.log");

        Files.createDirectories(insideFile1.getParent());
        Files.createDirectories(insideFile2.getParent());
        Files.createDirectories(outsideFile.getParent());

        Files.writeString(insideFile1, "trace data");
        Files.writeString(insideFile2, "png data");
        Files.writeString(outsideFile, "outside data");

        Instant t0 = Instant.parse("2026-08-16T00:00:00Z");
        TestArtifact a1 = new TestArtifact("module-flow", "flow-path", "trace.txt", insideFile1, "text/plain", t0, Map.of("env", "ci"));
        TestArtifact a2 = new TestArtifact("module-web", "screenshot", "screenshot.png", insideFile2, "image/png", t0, Map.of("resolution", "1920x1080"));
        TestArtifact a3 = new TestArtifact("module-reporting", "external-log", "outside.log", outsideFile, "text/plain", t0, Map.of("ext", "true"));

        ArtifactManifestWriter writer = new ArtifactManifestWriter();
        boolean written = writer.writeManifest(runRoot, "ci-run-1", List.of(a1, a2, a3), List.of("Warn: sample problem"));
        assertThat(written).isTrue();

        Path manifestFile = runRoot.resolve("manifest.json");
        String manifestContent = Files.readString(manifestFile);

        // The run root's absolute string must not appear anywhere in the manifest
        String runRootAbsStr = runRoot.toAbsolutePath().normalize().toString();
        assertThat(manifestContent).doesNotContain(runRootAbsStr);

        // The base tempDir absolute string must not appear anywhere in the manifest
        String tempDirAbsStr = tempDir.toAbsolutePath().normalize().toString();
        assertThat(manifestContent).doesNotContain(tempDirAbsStr);

        // Check each artifact's path and file properties in JSON to ensure all relative paths are clean
        ObjectMapper mapper = ArtifactManifestWriter.createDefaultObjectMapper();
        JsonNode rootNode = mapper.readTree(manifestFile.toFile());
        JsonNode artifacts = rootNode.get("artifacts");
        assertThat(artifacts.size()).isEqualTo(3);

        for (JsonNode artifact : artifacts) {
            String pathStr = artifact.get("path").asText();
            String fileStr = artifact.get("file").asText();

            assertThat(Paths.get(pathStr).isAbsolute())
                    .as("Path '%s' must not be absolute", pathStr)
                    .isFalse();
            assertThat(Paths.get(fileStr).isAbsolute())
                    .as("File '%s' must not be absolute", fileStr)
                    .isFalse();

            assertThat(pathStr).doesNotContain(":");
            assertThat(pathStr).doesNotStartWith("/");
            assertThat(fileStr).doesNotContain(":");
            assertThat(fileStr).doesNotStartWith("/");
        }
    }

    @Test
    void buildingManifestTwiceFromSameArtifactsYieldsByteIdenticalOutput(@TempDir Path tempDir) throws IOException {
        Path run1 = tempDir.resolve("build-1");
        Path run2 = tempDir.resolve("build-2");
        Files.createDirectories(run1);
        Files.createDirectories(run2);

        Instant t1 = Instant.parse("2026-08-16T12:00:00Z");
        Instant t2 = Instant.parse("2026-08-16T12:05:00Z");

        TestArtifact a1 = new TestArtifact("module-flow", "flow-path", "step-1", run1.resolve("module-flow/step-1.txt"), "text/plain", t1, Map.of("b", "2", "a", "1"));
        TestArtifact a2 = new TestArtifact("module-http", "http-log", "req-100", run1.resolve("module-http/req-100.log"), "text/plain", t2, Map.of("z", "99"));

        List<TestArtifact> artifactsRun1 = List.of(a1, a2);
        List<TestArtifact> artifactsRun2 = List.of(
                new TestArtifact("module-flow", "flow-path", "step-1", run2.resolve("module-flow/step-1.txt"), "text/plain", t1, Map.of("a", "1", "b", "2")),
                new TestArtifact("module-http", "http-log", "req-100", run2.resolve("module-http/req-100.log"), "text/plain", t2, Map.of("z", "99"))
        );

        List<String> problems = List.of("Problem A", "Problem B");

        ArtifactManifestWriter writer = new ArtifactManifestWriter();
        writer.writeManifest(run1, "fixed-ci-run", artifactsRun1, problems);
        writer.writeManifest(run2, "fixed-ci-run", artifactsRun2, problems);

        byte[] manifest1Bytes = Files.readAllBytes(run1.resolve("manifest.json"));
        byte[] manifest2Bytes = Files.readAllBytes(run2.resolve("manifest.json"));

        assertThat(Arrays.equals(manifest1Bytes, manifest2Bytes))
                .as("Building manifest twice from same artifacts must yield byte-identical output")
                .isTrue();
    }
}
