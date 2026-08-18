package io.testforge.db.contract.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.testforge.db.contract.model.DbSchemaSnapshot;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads and writes schema snapshots as Git-friendly JSON.
 *
 * <p>Output is pretty-printed with a trailing newline and a stable member order,
 * and the snapshot itself carries no timestamps, so re-capturing an unchanged
 * schema produces a byte-identical file. A committed baseline therefore shows up
 * in a git diff exactly when the database contract moved.
 */
public final class DbSchemaSnapshotStore {

    private final ObjectMapper objectMapper;

    public DbSchemaSnapshotStore() {
        this(new ObjectMapper());
    }

    public DbSchemaSnapshotStore(ObjectMapper objectMapper) {
        this.objectMapper = (objectMapper == null ? new ObjectMapper() : objectMapper.copy())
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Serializes a snapshot to JSON.
     *
     * @param snapshot the snapshot to serialize
     * @return the JSON document, ending with a newline
     */
    public String toJson(DbSchemaSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot) + System.lineSeparator();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to serialize schema snapshot", e);
        }
    }

    /**
     * Writes a snapshot, creating parent directories as needed.
     *
     * @param file     the file to write
     * @param snapshot the snapshot to write
     * @return the file that was written
     */
    public Path write(Path file, DbSchemaSnapshot snapshot) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, toJson(snapshot), StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write schema snapshot " + file, e);
        }
    }

    /**
     * Reads a snapshot.
     *
     * @param file the snapshot file
     * @return the snapshot
     * @throws UncheckedIOException when the file cannot be read or parsed
     */
    public DbSchemaSnapshot read(Path file) {
        try {
            return objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8), DbSchemaSnapshot.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read schema snapshot " + file, e);
        }
    }
}
