package io.testforge.db.contract.snapshot;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
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
 *
 * <p>Line endings are pinned to {@code \n} rather than taken from the platform.
 * Jackson's default indenter and {@code System.lineSeparator()} both follow the
 * host OS, which would rewrite every line of a baseline the first time it was
 * captured on Windows — a whole-file git diff for a schema that never moved.
 * "Byte-identical" has to hold across the machines a team actually runs on, not
 * only across runs on one of them.
 */
public final class DbSchemaSnapshotStore {

    /** Two-space indent, LF line endings, on every platform. */
    private static final DefaultIndenter INDENTER = new DefaultIndenter("  ", "\n");

    private static final String NEWLINE = "\n";

    private final ObjectMapper objectMapper;
    private final ObjectWriter writer;

    public DbSchemaSnapshotStore() {
        this(new ObjectMapper());
    }

    public DbSchemaSnapshotStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper.copy();
        DefaultPrettyPrinter prettyPrinter = new DefaultPrettyPrinter();
        prettyPrinter.indentObjectsWith(INDENTER);
        prettyPrinter.indentArraysWith(INDENTER);
        this.writer = this.objectMapper.writer(prettyPrinter);
    }

    /**
     * Serializes a snapshot to JSON.
     *
     * @param snapshot the snapshot to serialize
     * @return the JSON document, ending with a newline
     */
    public String toJson(DbSchemaSnapshot snapshot) {
        try {
            return writer.writeValueAsString(snapshot) + NEWLINE;
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
        DbSchemaSnapshot snapshot;
        try {
            snapshot = objectMapper.readValue(
                    Files.readString(file, StandardCharsets.UTF_8), DbSchemaSnapshot.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read schema snapshot " + file, e);
        }
        if (snapshot.formatVersion() != DbSchemaSnapshot.FORMAT_VERSION) {
            // Silently reading a foreign format is how a gate starts comparing
            // fields that mean different things — or missing ones entirely, and
            // calling the difference a schema change.
            throw new IllegalStateException("Schema snapshot " + file + " is format version "
                    + snapshot.formatVersion() + ", but this version of TestForge writes and reads format "
                    + DbSchemaSnapshot.FORMAT_VERSION + ". Re-capture the baseline with writeBaseline() "
                    + "and review the resulting diff before promoting it.");
        }
        return snapshot;
    }
}
