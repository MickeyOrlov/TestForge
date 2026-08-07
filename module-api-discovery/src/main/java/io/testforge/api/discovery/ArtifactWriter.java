package io.testforge.api.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Writes discovery artifacts under the configured output directory.
 *
 * <p>Same conventions as {@code ContractMonitorRunner}'s private helpers —
 * create parent directories, wrap {@link IOException} with the path in the
 * message, sanitize anything that becomes a file name. Extract the pair into
 * {@code core} when a third module needs it; two is not yet a pattern.
 */
public class ArtifactWriter {

    private final ObjectMapper objectMapper;

    public ArtifactWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void writeJson(Path path, Object value) {
        try {
            Files.createDirectories(path.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + path, e);
        }
    }

    public void writeString(Path path, String value) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, value, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + path, e);
        }
    }

    public static String safeFileName(String name) {
        String safe = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return safe.isBlank() ? "endpoint" : safe;
    }
}
