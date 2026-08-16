package io.testforge.artifact;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Descriptor describing one diagnostic file written by a producing module.
 * <p>
 * This is a descriptor, not a container: it points at a file the producer
 * already wrote.
 *
 * @param source    producing module id (e.g. "module-flow")
 * @param category  coarse kind (e.g. "flow-path", "resource-usage")
 * @param name      logical name, unique within source+category
 * @param file      path to the diagnostic file written on disk
 * @param mediaType media type of the file (e.g. "application/json", "text/plain")
 * @param createdAt timestamp when the descriptor was created
 * @param metadata  small, producer-supplied key-value metadata
 */
public record TestArtifact(
        String source,
        String category,
        String name,
        Path file,
        String mediaType,
        Instant createdAt,
        Map<String, String> metadata
) {

    public TestArtifact {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be null or blank");
        }
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("category must not be null or blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be null or blank");
        }
        Objects.requireNonNull(file, "file must not be null");

        if (mediaType == null || mediaType.isBlank()) {
            mediaType = "application/octet-stream";
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        metadata = metadata == null || metadata.isEmpty() ? Map.of() : Map.copyOf(metadata);
    }
}
