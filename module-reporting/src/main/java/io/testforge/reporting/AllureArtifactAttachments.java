package io.testforge.reporting;

import io.qameta.allure.Allure;
import io.testforge.artifact.TestArtifact;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Attaches test diagnostic artifacts to the current Allure test.
 *
 * <p>Optional dependency: compiled against
 * {@code io.qameta.allure:allure-java-commons}, which must be on the runtime
 * classpath of the test module that calls this. The reporting module itself works
 * without Allure.
 *
 * <p>All attachment methods are best-effort: missing or unreadable files, or an
 * absent Allure runtime dependency (e.g. {@link NoClassDefFoundError} or {@link LinkageError}),
 * are caught and logged at WARN level. Attaching a diagnostic will never fail a test.
 */
public final class AllureArtifactAttachments {

    private static final Logger log = LoggerFactory.getLogger(AllureArtifactAttachments.class);

    private AllureArtifactAttachments() {
    }

    /**
     * Attaches a single {@link TestArtifact} to the current Allure test.
     *
     * @param artifact the artifact descriptor to attach
     */
    public static void attach(TestArtifact artifact) {
        if (artifact == null) {
            log.warn("Attempted to attach null artifact to Allure");
            return;
        }
        try {
            Path file = artifact.file();
            if (file == null || !Files.exists(file) || !Files.isReadable(file)) {
                log.warn("Cannot attach artifact '{}' to Allure: file '{}' is missing or unreadable",
                        artifact.name(), file);
                return;
            }
            String name = (artifact.name() != null && !artifact.name().isBlank())
                    ? artifact.name()
                    : "artifact";
            String mediaType = (artifact.mediaType() != null && !artifact.mediaType().isBlank())
                    ? artifact.mediaType()
                    : "application/octet-stream";
            String extension = deriveExtension(artifact);

            try (InputStream stream = Files.newInputStream(file)) {
                Allure.addAttachment(name, mediaType, stream, extension);
            }
        } catch (Throwable t) {
            log.warn("Failed to attach artifact '{}' to Allure: {}",
                    artifact != null ? artifact.name() : "unknown", t.getMessage(), t);
        }
    }

    /**
     * Attaches a collection of {@link TestArtifact}s to the current Allure test.
     *
     * @param artifacts collection of artifacts to attach
     */
    public static void attach(Collection<TestArtifact> artifacts) {
        attachAll(artifacts);
    }

    /**
     * Attaches a collection of {@link TestArtifact}s to the current Allure test.
     *
     * @param artifacts collection of artifacts to attach
     */
    public static void attachAll(Collection<TestArtifact> artifacts) {
        if (artifacts == null) {
            log.warn("Attempted to attach null artifacts collection to Allure");
            return;
        }
        try {
            for (TestArtifact artifact : artifacts) {
                attach(artifact);
            }
        } catch (Throwable t) {
            log.warn("Failed to attach artifacts collection to Allure: {}", t.getMessage(), t);
        }
    }

    private static String deriveExtension(TestArtifact artifact) {
        if (artifact == null || artifact.file() == null) {
            return "";
        }
        Path file = artifact.file();
        if (file.getFileName() != null) {
            String fileName = file.getFileName().toString();
            int dotIndex = fileName.lastIndexOf('.');
            if (dotIndex > 0 && dotIndex < fileName.length() - 1) {
                return fileName.substring(dotIndex);
            }
        }
        String mediaType = artifact.mediaType();
        if (mediaType != null) {
            String lower = mediaType.toLowerCase(Locale.ROOT);
            if (lower.contains("json")) {
                return ".json";
            }
            if (lower.contains("html")) {
                return ".html";
            }
            if (lower.contains("plain") || lower.contains("text")) {
                return ".txt";
            }
            if (lower.contains("png")) {
                return ".png";
            }
            if (lower.contains("jpeg") || lower.contains("jpg")) {
                return ".jpg";
            }
            if (lower.contains("xml")) {
                return ".xml";
            }
        }
        return "";
    }
}
