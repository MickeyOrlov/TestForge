package io.testforge.artifact;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * Seam through which producing modules publish diagnostic artifacts.
 * <p>
 * <strong>CRITICAL CONTRACT:</strong> Reporting is best-effort and SECONDARY.
 * No method on this interface may ever throw — a diagnostic that cannot be
 * written must never replace the failure that made the run interesting.
 * Implementations swallow and log their own failures.
 */
public interface ArtifactSink {

    /**
     * Complete no-op implementation returning a harmless temporary/absent path
     * and doing nothing else, so a producer works unchanged when module-reporting
     * is not on the classpath.
     */
    ArtifactSink NO_OP = new ArtifactSink() {
        @Override
        public Path directoryFor(String source) {
            return Path.of(System.getProperty("java.io.tmpdir"), "testforge-noop");
        }

        @Override
        public void register(TestArtifact artifact) {
            // no-op
        }

        @Override
        public TestArtifact write(String source, String category, String name, String mediaType, String content) {
            try {
                String safeSource = (source != null && !source.isBlank()) ? source : "unknown";
                String safeCategory = (category != null && !category.isBlank()) ? category : "unknown";
                String safeName = (name != null && !name.isBlank()) ? name : "artifact.tmp";
                Path safeFile = directoryFor(safeSource).resolve(safeName);
                return new TestArtifact(safeSource, safeCategory, safeName, safeFile, mediaType, Instant.now(), Map.of());
            } catch (Exception e) {
                return new TestArtifact("unknown", "unknown", "artifact.tmp",
                        Path.of(System.getProperty("java.io.tmpdir"), "testforge-noop", "artifact.tmp"),
                        mediaType, Instant.now(), Map.of());
            }
        }
    };

    /**
     * Returns a run-scoped directory the producer may write into, created on demand.
     *
     * @param source producing module id
     * @return a run-scoped directory path for the given source
     */
    Path directoryFor(String source);

    /**
     * Describes a file the producer already wrote.
     *
     * @param artifact the artifact descriptor to register
     */
    void register(TestArtifact artifact);

    /**
     * Convenience method for small text/JSON diagnostics; writes the file AND registers it.
     *
     * @param source    producing module id
     * @param category  coarse kind of artifact
     * @param name      logical name, unique within source+category
     * @param mediaType media type of the content
     * @param content   textual content to write
     * @return the registered TestArtifact descriptor
     */
    TestArtifact write(String source, String category, String name, String mediaType, String content);

    /**
     * Registers a file the producer already wrote in its own output directory,
     * for runners whose reports live outside the run root (a configured
     * {@code output-dir}). Never throws: an invalid argument or a failing sink
     * is swallowed like every other reporting failure.
     *
     * <p>The manifest only keeps the file name for such files, so a relative
     * {@code file} is also recorded as {@code location} metadata — the path as
     * configured, e.g. {@code build/api-discovery/report.json}. An absolute
     * path is never recorded: it would leak the local directory layout.
     *
     * @param source    producing module id
     * @param category  coarse kind of artifact
     * @param name      logical name, unique within source+category
     * @param file      the file already written
     * @param mediaType media type of the file
     * @param metadata  small, safe producer metadata; may be {@code null}
     */
    default void registerFile(String source, String category, String name, Path file, String mediaType,
                              Map<String, String> metadata) {
        try {
            Map<String, String> combined = new java.util.LinkedHashMap<>(metadata == null ? Map.of() : metadata);
            if (file != null && !file.isAbsolute()) {
                combined.put("location", file.toString().replace('\\', '/'));
            }
            register(new TestArtifact(source, category, name, file, mediaType, Instant.now(), combined));
        } catch (RuntimeException e) {
            // best-effort by contract: a diagnostic must never fail the producer
        }
    }
}
