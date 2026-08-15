package io.testforge.reporting;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.testforge.artifact.TestArtifact;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deterministically writes manifest.json containing metadata and relative artifact paths for a test run.
 *
 * <p>Requirements:
 * <ul>
 *   <li>Deterministic: stable key order and artifact sorting (createdAt, source, category, name).</li>
 *   <li>Paths stored relative to the run root (never absolute).</li>
 *   <li>Metadata copied directly as producer supplied (file contents are never read).</li>
 *   <li>Best-effort: failure logs WARN and returns boolean/optional without throwing exceptions.</li>
 * </ul>
 */
public class ArtifactManifestWriter {

    private static final Logger log = LoggerFactory.getLogger(ArtifactManifestWriter.class);

    /** @see ArtifactOrdering#DETERMINISTIC */
    public static final Comparator<TestArtifact> ARTIFACT_COMPARATOR = ArtifactOrdering.DETERMINISTIC;

    private final ObjectMapper mapper;

    public ArtifactManifestWriter() {
        this(createDefaultObjectMapper());
    }

    public ArtifactManifestWriter(ObjectMapper mapper) {
        this.mapper = mapper != null ? mapper : createDefaultObjectMapper();
    }

    public static ObjectMapper createDefaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        return mapper;
    }

    public boolean writeManifest(Path runRoot, String runId, List<TestArtifact> artifacts, List<String> reportingProblems) {
        return write(runRoot, runId, artifacts, reportingProblems).isPresent();
    }

    public boolean writeManifest(ArtifactRunLayout layout, List<TestArtifact> artifacts, List<String> reportingProblems) {
        return write(layout, artifacts, reportingProblems).isPresent();
    }

    public Optional<Path> write(ArtifactRunLayout layout, List<TestArtifact> artifacts, List<String> reportingProblems) {
        if (layout == null) {
            log.warn("Cannot write manifest: layout is null");
            return Optional.empty();
        }
        return write(layout.runRoot(), layout.runId(), layout, artifacts, reportingProblems);
    }

    public Optional<Path> write(Path runRoot, String runId, List<TestArtifact> artifacts, List<String> reportingProblems) {
        if (runRoot == null) {
            log.warn("Cannot write manifest: runRoot is null");
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

            List<ManifestArtifact> manifestArtifacts = safeArtifacts.stream()
                    .map(a -> {
                        String relPath = toRelativePathString(layout, runRoot, a.file());
                        Map<String, String> meta = a.metadata() != null ? new TreeMap<>(a.metadata()) : Map.of();
                        String createdStr = a.createdAt() != null ? a.createdAt().toString() : Instant.now().toString();
                        return new ManifestArtifact(
                                a.source(),
                                a.category(),
                                a.name(),
                                relPath,
                                a.mediaType(),
                                createdStr,
                                meta
                        );
                    })
                    .toList();

            ManifestData data = new ManifestData(
                    runId,
                    manifestArtifacts.size(),
                    safeProblems,
                    manifestArtifacts
            );

            Path targetFile = runRoot.resolve("manifest.json");
            Files.createDirectories(runRoot);
            mapper.writeValue(targetFile.toFile(), data);
            return Optional.of(targetFile);
        } catch (Exception e) {
            log.warn("Failed to write manifest.json to {}: {}", runRoot, e.getMessage(), e);
            return Optional.empty();
        }
    }

    static String toRelativePathString(ArtifactRunLayout layout, Path runRoot, Path target) {
        if (target == null) {
            return "";
        }
        Path rel = null;
        if (layout != null) {
            rel = layout.relativize(target);
        } else if (runRoot != null) {
            try {
                Path normalizedRoot = runRoot.toAbsolutePath().normalize();
                Path normalizedTarget = target.toAbsolutePath().normalize();
                if (normalizedTarget.startsWith(normalizedRoot)) {
                    rel = normalizedRoot.relativize(normalizedTarget);
                } else {
                    rel = target.getFileName();
                }
            } catch (Exception ignored) {
            }
        }
        if (rel == null) {
            rel = target.getFileName() != null ? target.getFileName() : target;
        }
        String pathStr = rel.toString().replace('\\', '/');
        // Ensure no absolute path prefix remains
        if (pathStr.startsWith("/")) {
            pathStr = pathStr.substring(1);
        }
        return pathStr;
    }

    @JsonPropertyOrder({"runId", "artifactCount", "reportingProblems", "artifacts"})
    public record ManifestData(
            String runId,
            int artifactCount,
            List<String> reportingProblems,
            List<ManifestArtifact> artifacts
    ) {}

    @JsonPropertyOrder({"source", "category", "name", "path", "mediaType", "createdAt", "metadata"})
    public record ManifestArtifact(
            String source,
            String category,
            String name,
            String path,
            String mediaType,
            String createdAt,
            Map<String, String> metadata
    ) {}
}
