package io.testforge.reporting;

import io.testforge.artifact.ArtifactSink;
import io.testforge.artifact.TestArtifact;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Run-scoped {@link ArtifactSink} implementation for TestForge reporting.
 *
 * <p>Collects diagnostic descriptors written by test modules, provides thread-safe
 * artifact registration, collision-free file writing via {@link ArtifactRunLayout},
 * and deterministic snapshot ordering.
 *
 * <p><strong>CRITICAL CONTRACT:</strong> Reporting is best-effort and SECONDARY.
 * No method on this class may ever throw — a diagnostic that cannot be written
 * must never replace the failure that made the run interesting.
 */
public class RunArtifactSink implements ArtifactSink {

    private static final Logger log = LoggerFactory.getLogger(RunArtifactSink.class);

    private final ArtifactRunLayout layout;
    private final Collection<TestArtifact> registeredArtifacts = new ConcurrentLinkedQueue<>();
    private final Collection<ReportingProblem> recordedProblems = new ConcurrentLinkedQueue<>();

    public RunArtifactSink(ArtifactRunLayout layout) {
        this.layout = layout != null ? layout : new ArtifactRunLayout();
    }

    public ArtifactRunLayout layout() {
        return layout;
    }

    @Override
    public Path directoryFor(String source) {
        try {
            return layout.directoryFor(source);
        } catch (Throwable t) {
            log.warn("Failed to get directory for source '{}': {}", source, t.getMessage(), t);
            recordProblem("directoryFor", t);
            return fallbackDirectory(source);
        }
    }

    @Override
    public void register(TestArtifact artifact) {
        try {
            if (artifact == null) {
                log.warn("Attempted to register null artifact");
                recordProblem("register", new IllegalArgumentException("artifact must not be null"));
                return;
            }
            registeredArtifacts.add(artifact);
        } catch (Throwable t) {
            log.warn("Failed to register artifact '{}': {}", artifact, t.getMessage(), t);
            recordProblem("register", t);
        }
    }

    @Override
    public TestArtifact write(String source, String category, String name, String mediaType, String content) {
        String safeSource = (source != null && !source.isBlank()) ? source : "unknown";
        String safeCategory = (category != null && !category.isBlank()) ? category : "unknown";
        String safeName = (name != null && !name.isBlank()) ? name : "artifact.tmp";
        String safeMediaType = (mediaType != null && !mediaType.isBlank()) ? mediaType : "application/octet-stream";
        String safeContent = content != null ? content : "";

        Path targetFile = null;
        try {
            targetFile = layout.resolveUniqueFile(safeSource, safeName);
            Files.writeString(targetFile, safeContent, StandardCharsets.UTF_8);

            TestArtifact artifact = new TestArtifact(
                    safeSource,
                    safeCategory,
                    safeName,
                    targetFile,
                    safeMediaType,
                    Instant.now(),
                    Map.of()
            );
            register(artifact);
            return artifact;
        } catch (Throwable t) {
            log.warn("Failed to write artifact [source={}, category={}, name={}]: {}", safeSource, safeCategory, safeName, t.getMessage(), t);
            recordProblem("write", t);

            Path fallbackFile = targetFile != null
                    ? targetFile
                    : fallbackFilePath(safeSource, safeName);

            // Sanitised: filesystem exception messages carry ABSOLUTE paths, and this map is
            // copied verbatim into manifest.json, which would leak the local username.
            Map<String, String> metadata = Map.of(
                    "writeFailed", "true",
                    "error", t.getMessage() != null
                            ? DiagnosticText.sanitise(t.getMessage())
                            : t.getClass().getName()
            );

            TestArtifact failedArtifact = new TestArtifact(
                    safeSource,
                    safeCategory,
                    safeName,
                    fallbackFile,
                    safeMediaType,
                    Instant.now(),
                    metadata
            );

            try {
                register(failedArtifact);
            } catch (Throwable ignored) {
            }

            return failedArtifact;
        }
    }

    /**
     * Returns an immutable, deterministically ordered snapshot of all registered artifacts.
     *
     * <p>Ordering: {@code createdAt} -&gt; {@code source} -&gt; {@code category} -&gt; {@code name} -&gt; {@code file}.
     *
     * @return immutable snapshot of registered artifacts
     */
    public List<TestArtifact> artifacts() {
        try {
            List<TestArtifact> snapshot = new ArrayList<>(registeredArtifacts);
            snapshot.sort(ArtifactOrdering.DETERMINISTIC);
            return Collections.unmodifiableList(snapshot);
        } catch (Throwable t) {
            log.warn("Failed to retrieve ordered artifacts snapshot: {}", t.getMessage(), t);
            recordProblem("artifacts", t);
            return List.of();
        }
    }

    /**
     * Returns an immutable snapshot of all recorded reporting problems.
     *
     * @return immutable snapshot of problems
     */
    public List<ReportingProblem> problems() {
        try {
            return List.copyOf(recordedProblems);
        } catch (Throwable t) {
            log.warn("Failed to retrieve problems snapshot: {}", t.getMessage(), t);
            return List.of();
        }
    }

    private void recordProblem(String operation, Throwable t) {
        try {
            recordedProblems.add(new ReportingProblem(operation, t));
        } catch (Throwable ignored) {
        }
    }

    private Path fallbackDirectory(String source) {
        // This runs only when the layout itself failed, so it cannot lean on the
        // layout's sanitisation — a source of "../../etc" would otherwise resolve
        // above the run root. The error path must not be the one that escapes.
        String safeSource = safeSegment(source);
        try {
            if (layout != null && layout.runRoot() != null) {
                return layout.runRoot().resolve(safeSource);
            }
        } catch (Throwable ignored) {
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "testforge-fallback", safeSource);
    }

    private static String safeSegment(String source) {
        if (source == null || source.isBlank()) {
            return "unknown";
        }
        String cleaned = source.replaceAll("[^a-zA-Z0-9_.-]", "_");
        return cleaned.isBlank() || cleaned.replace(".", "").isEmpty() ? "unknown" : cleaned;
    }

    private Path fallbackFilePath(String source, String name) {
        try {
            return fallbackDirectory(source).resolve(name != null ? name : "artifact.tmp");
        } catch (Throwable ignored) {
            return Path.of(System.getProperty("java.io.tmpdir"), "testforge-fallback", name != null ? name : "artifact.tmp");
        }
    }
}
