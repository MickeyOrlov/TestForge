package io.testforge.reporting;

import io.testforge.artifact.TestArtifact;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes summary.md in the run root as a human-readable index of test artifacts grouped by source.
 *
 * <p>Requirements:
 * <ul>
 *   <li>Markdown index grouped by source, with category, name and relative path.</li>
 *   <li>Section listing reporting problems when any exist.</li>
 *   <li>Best-effort: failure logs WARN and returns boolean/optional without throwing exceptions.</li>
 * </ul>
 */
public class ArtifactSummaryWriter {

    private static final Logger log = LoggerFactory.getLogger(ArtifactSummaryWriter.class);

    public boolean writeSummary(Path runRoot, String runId, List<TestArtifact> artifacts, List<String> reportingProblems) {
        return write(runRoot, runId, artifacts, reportingProblems).isPresent();
    }

    public boolean writeSummary(ArtifactRunLayout layout, List<TestArtifact> artifacts, List<String> reportingProblems) {
        return write(layout, artifacts, reportingProblems).isPresent();
    }

    public Optional<Path> write(ArtifactRunLayout layout, List<TestArtifact> artifacts, List<String> reportingProblems) {
        if (layout == null) {
            log.warn("Cannot write summary: layout is null");
            return Optional.empty();
        }
        return write(layout.runRoot(), layout.runId(), layout, artifacts, reportingProblems);
    }

    public Optional<Path> write(Path runRoot, String runId, List<TestArtifact> artifacts, List<String> reportingProblems) {
        if (runRoot == null) {
            log.warn("Cannot write summary: runRoot is null");
            return Optional.empty();
        }
        String effectiveRunId = (runId != null && !runId.isBlank())
                ? runId
                : (runRoot.getFileName() != null ? runRoot.getFileName().toString() : "unknown-run");
        ArtifactRunLayout layout = new ArtifactRunLayout(runRoot.getParent(), effectiveRunId);
        return write(runRoot, effectiveRunId, layout, artifacts, reportingProblems);
    }

    private Optional<Path> write(Path runRoot, String runId, ArtifactRunLayout layout, List<TestArtifact> artifacts, List<String> reportingProblems) {
        try {
            List<TestArtifact> safeArtifacts = artifacts != null
                    ? artifacts.stream().filter(Objects::nonNull).sorted(ArtifactOrdering.DETERMINISTIC).toList()
                    : List.of();
            List<String> safeProblems = reportingProblems != null ? List.copyOf(reportingProblems) : List.of();

            StringBuilder sb = new StringBuilder();
            sb.append("# Test Run Summary: ").append(runId).append("\n\n");
            sb.append("**Artifact Count:** ").append(safeArtifacts.size()).append("\n\n");

            List<TestArtifact> attention = safeArtifacts.stream().filter(ArtifactSummaryWriter::needsAttention).toList();
            if (!attention.isEmpty()) {
                sb.append("## Needs attention\n\n");
                for (TestArtifact artifact : attention) {
                    sb.append("- **").append(artifact.source()).append("** ").append(artifact.name()).append(": ")
                            .append(displayPath(layout, runRoot, artifact))
                            .append(status(artifact)).append("\n");
                }
                sb.append("\n");
            }

            Map<String, List<TestArtifact>> failedTests = failedTests(safeArtifacts);
            if (!failedTests.isEmpty()) {
                sb.append("## Failed tests\n\n");
                failedTests.forEach((test, evidence) -> {
                    sb.append("### ").append(test).append("\n\n");
                    for (TestArtifact artifact : evidence) {
                        sb.append("- ").append(artifact.source()).append(" (").append(artifact.category())
                                .append("): ").append(displayPath(layout, runRoot, artifact)).append("\n");
                    }
                    sb.append("\n");
                });
            }

            sb.append("## Diagnostics by Source\n\n");
            if (safeArtifacts.isEmpty()) {
                sb.append("No artifacts recorded.\n\n");
            } else {
                Map<String, List<TestArtifact>> groupedBySource = safeArtifacts.stream()
                        .collect(Collectors.groupingBy(
                                TestArtifact::source,
                                LinkedHashMap::new,
                                Collectors.toList()
                        ));

                for (Map.Entry<String, List<TestArtifact>> entry : groupedBySource.entrySet()) {
                    sb.append("### ").append(entry.getKey()).append("\n\n");
                    for (TestArtifact artifact : entry.getValue()) {
                        sb.append("- **").append(artifact.name()).append("** (")
                                .append(artifact.category()).append("): ")
                                .append(displayPath(layout, runRoot, artifact))
                                .append(status(artifact)).append("\n");
                    }
                    sb.append("\n");
                }
            }

            if (!safeProblems.isEmpty()) {
                sb.append("## Reporting Problems\n\n");
                for (String problem : safeProblems) {
                    sb.append("- ").append(problem).append("\n");
                }
                sb.append("\n");
            }

            Path targetFile = runRoot.resolve("summary.md");
            Files.createDirectories(runRoot);
            Files.writeString(targetFile, sb.toString(), StandardCharsets.UTF_8);
            return Optional.of(targetFile);
        } catch (Exception e) {
            log.warn("Failed to write summary.md to {}: {}", runRoot, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * A failed test's evidence from every module, under the test: the HTTP
     * exchanges, the SQL, the Kafka traffic and the screenshot of one failure
     * read together. The key is metadata {@code testKey}, else — for a
     * {@code failed-test-*} report named {@code <Class>-<method>.md} — the
     * name without its extension, else metadata {@code test}.
     */
    static Map<String, List<TestArtifact>> failedTests(List<TestArtifact> artifacts) {
        Map<String, List<TestArtifact>> byTest = new java.util.TreeMap<>();
        for (TestArtifact artifact : artifacts) {
            String key = testKey(artifact);
            if (key != null) {
                byTest.computeIfAbsent(key, ignored -> new java.util.ArrayList<>()).add(artifact);
            }
        }
        return byTest;
    }

    private static String testKey(TestArtifact artifact) {
        Map<String, String> metadata = artifact.metadata() == null ? Map.of() : artifact.metadata();
        if (metadata.get("testKey") != null && !metadata.get("testKey").isBlank()) {
            return metadata.get("testKey");
        }
        String category = artifact.category() == null ? "" : artifact.category();
        if (category.startsWith("failed-test")) {
            String name = artifact.name() == null ? "" : artifact.name();
            int dot = name.lastIndexOf('.');
            return dot > 0 ? name.substring(0, dot) : name;
        }
        String test = metadata.get("test");
        return category.endsWith("-failure") && test != null && !test.isBlank() ? test : null;
    }

    /** Metadata keys that describe a producer's verdict, in display order. */
    private static final List<String> STATUS_KEYS = List.of("healthy", "compatible", "outcome");

    /**
     * Files inside the run root show their run-relative path. A file a producer
     * kept in its own output directory shows its {@code location} metadata — the
     * project-relative path it was configured with — so the summary points at a
     * real file instead of a bare name. A location that could leak the local
     * layout (absolute, drive-qualified, or climbing with {@code ..}) is ignored.
     */
    private static String displayPath(ArtifactRunLayout layout, Path runRoot, TestArtifact artifact) {
        String relPath = ArtifactManifestWriter.toRelativePathString(layout, runRoot, artifact.file());
        String location = artifact.metadata().get("location");
        if (location == null || insideRunRoot(runRoot, artifact.file()) || !safeLocation(location)) {
            return "`" + relPath + "`";
        }
        return "`" + location.replace('\\', '/') + "` (outside the run directory)";
    }

    private static boolean insideRunRoot(Path runRoot, Path file) {
        try {
            return file.toAbsolutePath().normalize().startsWith(runRoot.toAbsolutePath().normalize());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean safeLocation(String location) {
        String normalized = location.replace('\\', '/');
        return !normalized.isBlank()
                && !normalized.startsWith("/")
                && !normalized.contains(":")
                && !normalized.equals("..")
                && !normalized.startsWith("../")
                && !normalized.contains("/../");
    }

    /** " — healthy: false, outcome: FINDINGS", or empty when the producer reported no verdict. */
    private static String status(TestArtifact artifact) {
        List<String> parts = new java.util.ArrayList<>();
        for (String key : STATUS_KEYS) {
            String value = artifact.metadata().get(key);
            if (value != null && !value.isBlank()) {
                parts.add(key + ": " + value);
            }
        }
        return parts.isEmpty() ? "" : " — " + String.join(", ", parts);
    }

    /** A report whose own verdict is negative: the first thing a CI reader needs. */
    static boolean needsAttention(TestArtifact artifact) {
        Map<String, String> metadata = artifact.metadata();
        String outcome = metadata.getOrDefault("outcome", "");
        return "false".equalsIgnoreCase(metadata.get("healthy"))
                || "false".equalsIgnoreCase(metadata.get("compatible"))
                || outcome.equals("FINDINGS")
                || outcome.endsWith("_ERROR");
    }
}
