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
                        String relPath = ArtifactManifestWriter.toRelativePathString(layout, runRoot, artifact.file());
                        sb.append("- **").append(artifact.name()).append("** (")
                                .append(artifact.category()).append("): `")
                                .append(relPath).append("`\n");
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
}
