package io.testforge.reporting;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages run-scoped directory layout and collision-free artifact paths for unified reporting.
 *
 * <p>Allocates a single run root directory named with a time-sortable run ID, provides
 * sanitised per-source subdirectories, collision-free file naming across concurrent threads,
 * and security-conscious relative path resolution.
 *
 * <p>All directory creation and path allocation operations are best-effort: failures log a
 * warning and return usable fallbacks rather than throwing exceptions.
 */
public class ArtifactRunLayout {

    public static final Path DEFAULT_BASE_DIR = Paths.get("build/testforge-artifacts");

    private static final Logger log = LoggerFactory.getLogger(ArtifactRunLayout.class);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private final Path baseDir;
    private final String runId;
    private final Path runRoot;
    private final Set<Path> allocatedFiles = ConcurrentHashMap.newKeySet();

    public ArtifactRunLayout() {
        this(DEFAULT_BASE_DIR, null);
    }

    public ArtifactRunLayout(Path baseDir) {
        this(baseDir, null);
    }

    public ArtifactRunLayout(String baseDir) {
        this(baseDir != null ? Paths.get(baseDir) : DEFAULT_BASE_DIR, null);
    }

    public ArtifactRunLayout(String baseDir, String runId) {
        this(baseDir != null ? Paths.get(baseDir) : DEFAULT_BASE_DIR, runId);
    }

    public ArtifactRunLayout(Path baseDir, String runId) {
        this.baseDir = baseDir != null ? baseDir : DEFAULT_BASE_DIR;
        this.runId = (runId != null && !runId.isBlank()) ? sanitizeRunId(runId) : generateRunId();
        this.runRoot = initializeRunRoot(this.baseDir, this.runId);
    }

    public Path getBaseDir() {
        return baseDir;
    }

    public Path baseDir() {
        return baseDir;
    }

    public String getRunId() {
        return runId;
    }

    public String runId() {
        return runId;
    }

    public Path getRunRoot() {
        return runRoot;
    }

    public Path runRoot() {
        return runRoot;
    }

    /**
     * Returns a per-source subdirectory inside the run root, created on demand.
     *
     * <p>The source string is sanitised to ensure it cannot escape the run root.
     *
     * @param source the name of the producing module or context
     * @return path to the source directory
     */
    public Path directoryFor(String source) {
        String sanitized = sanitizeSource(source);
        Path subDir = runRoot.resolve(sanitized).normalize();
        if (!subDir.startsWith(runRoot.normalize())) {
            log.warn("Sanitised source path '{}' escaped run root '{}'; forcing contained path.", subDir, runRoot);
            subDir = runRoot.resolve("sanitised-" + UUID.randomUUID());
        }
        try {
            Files.createDirectories(subDir);
        } catch (Exception e) {
            log.warn("Failed to create directory for source '{}' at {}: {}", source, subDir, e.getMessage(), e);
        }
        return subDir;
    }

    /**
     * Allocates a collision-free file path within a source directory for a logical file name.
     *
     * <p>Safe for concurrent invocation across threads.
     *
     * @param source the name of the producing module
     * @param logicalName the desired file name or logical name
     * @return a distinct file path that does not overwrite existing files
     */
    public Path resolveUniqueFile(String source, String logicalName) {
        Path sourceDir = directoryFor(source);
        return resolveUniqueFile(sourceDir, logicalName);
    }

    /**
     * Allocates a collision-free file path within a target directory for a logical file name.
     *
     * <p>Safe for concurrent invocation across threads.
     *
     * @param directory target directory
     * @param logicalName desired file name or logical name
     * @return a distinct file path that does not overwrite existing files
     */
    public Path resolveUniqueFile(Path directory, String logicalName) {
        Path targetDir = directory != null ? directory : runRoot;
        try {
            Files.createDirectories(targetDir);
        } catch (Exception e) {
            log.warn("Failed to create target directory {}: {}", targetDir, e.getMessage(), e);
        }

        String sanitized = sanitizeFileName(logicalName);
        String baseName;
        String extension;
        int dotIndex = sanitized.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = sanitized.substring(0, dotIndex);
            extension = sanitized.substring(dotIndex);
        } else {
            baseName = sanitized;
            extension = "";
        }

        int counter = 0;
        while (true) {
            String fileName = (counter == 0) ? baseName + extension : baseName + "-" + counter + extension;
            Path candidate = targetDir.resolve(fileName).normalize();

            if (allocatedFiles.add(candidate)) {
                try {
                    Files.createFile(candidate);
                    return candidate;
                } catch (FileAlreadyExistsException e) {
                    // File already existed on disk, try next candidate
                } catch (Exception e) {
                    log.warn("Failed to create file at {}: {}", candidate, e.getMessage(), e);
                    return candidate;
                }
            }
            counter++;
            if (counter > 10000) {
                Path fallback = targetDir.resolve(baseName + "-" + UUID.randomUUID() + extension);
                try {
                    Files.createFile(fallback);
                } catch (Exception ignored) {
                }
                return fallback;
            }
        }
    }

    public Path uniqueFile(String source, String logicalName) {
        return resolveUniqueFile(source, logicalName);
    }

    public Path uniqueFile(Path directory, String logicalName) {
        return resolveUniqueFile(directory, logicalName);
    }

    /**
     * Expresses an artifact path relative to the run root.
     *
     * <p>Used for manifests to avoid leaking absolute local directory structure or usernames.
     * Handles paths outside the run root gracefully without throwing exceptions.
     *
     * @param target path to express relative to run root
     * @return relative path, or absolute-free filename fallback if outside run root or error occurs
     */
    public Path relativize(Path target) {
        if (target == null) {
            return Paths.get("");
        }
        try {
            Path normalizedRunRoot = runRoot.toAbsolutePath().normalize();
            Path normalizedTarget = target.toAbsolutePath().normalize();

            if (normalizedTarget.startsWith(normalizedRunRoot)) {
                return normalizedRunRoot.relativize(normalizedTarget);
            }
        } catch (Exception e) {
            log.warn("Failed to relativize path {} against run root {}: {}", target, runRoot, e.getMessage(), e);
        }

        Path fileName = target.getFileName();
        if (fileName != null) {
            return fileName;
        }
        String raw = target.toString().replaceAll("^[/\\\\:]+", "");
        return Paths.get(raw.isEmpty() ? "unknown" : raw);
    }

    public Path relativize(String targetPath) {
        return relativize(targetPath != null ? Paths.get(targetPath) : null);
    }

    private static String generateRunId() {
        String timestamp = TIMESTAMP_FORMATTER.format(Instant.now());
        int randomNum = ThreadLocalRandom.current().nextInt(0x100000, 0x1000000);
        String randomSuffix = Integer.toHexString(randomNum);
        return timestamp + "-" + randomSuffix;
    }

    private static String sanitizeRunId(String rawRunId) {
        String sanitized = rawRunId.replaceAll("[/\\\\:\\x00]", "_").replaceAll("\\.\\.+", "_");
        sanitized = sanitized.trim();
        return sanitized.isEmpty() ? generateRunId() : sanitized;
    }

    private static Path initializeRunRoot(Path baseDir, String runId) {
        Path candidate = baseDir.resolve(runId);
        try {
            Files.createDirectories(candidate);
            return candidate;
        } catch (Exception e) {
            log.warn(
                    "Failed to create run root directory at {}: {}. Falling back to temp directory.",
                    candidate,
                    e.getMessage(),
                    e);
            try {
                Path fallback = Paths.get(System.getProperty("java.io.tmpdir"), "testforge-artifacts", runId);
                Files.createDirectories(fallback);
                return fallback;
            } catch (Exception ex) {
                log.warn("Failed to create fallback run root directory: {}", ex.getMessage(), ex);
                return candidate;
            }
        }
    }

    private static String sanitizeSource(String source) {
        if (source == null || source.isBlank()) {
            return "unknown-source";
        }
        String sanitized = source.trim();
        sanitized = sanitized.replaceAll("[/\\\\:\\x00]", "_");
        sanitized = sanitized.replaceAll("\\.\\.+", "_");
        sanitized = sanitized.replaceAll("[^a-zA-Z0-9_\\.-]", "_");
        sanitized = sanitized.replaceAll("^[.\\s]+|[.\\s]+$", "");
        if (sanitized.isEmpty()) {
            return "unknown-source";
        }
        return sanitized;
    }

    private static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "artifact";
        }
        String sanitized = fileName.trim();
        sanitized = sanitized.replaceAll("[/\\\\:\\x00]", "_");
        sanitized = sanitized.replaceAll("\\.\\.+", "_");
        sanitized = sanitized.replaceAll("^[.\\s]+|[.\\s]+$", "");
        if (sanitized.isEmpty()) {
            return "artifact";
        }
        return sanitized;
    }
}
