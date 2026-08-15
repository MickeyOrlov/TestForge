package io.testforge.api.fuzz;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FuzzEvidenceWriter {

    private final ObjectMapper objectMapper;

    public FuzzEvidenceWriter() {
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Writes to {@code <outputDir>/<runId>/<specId>/run.json}.
     *
     * <p>The spec segment is not decoration: one run fuzzes every configured
     * spec, so a path keyed only on the run id means each spec overwrites the
     * previous one's evidence and only the last survives — which would quietly
     * break the module's reproducibility promise exactly when there is most to
     * reproduce. Deriving the directory here rather than at the call site means
     * no caller can reintroduce that.
     */
    public void writeEvidence(Path outputDir, FuzzRunEvidence evidence) {
        Path runDir = outputDir.resolve(evidence.runId());
        String specSegment = evidence.specId() == null ? "" : evidence.specId().replaceAll("[^A-Za-z0-9._-]", "_");
        if (!specSegment.isBlank()) {
            runDir = runDir.resolve(specSegment);
        }
        Path runJson = runDir.resolve("run.json");
        try {
            Files.createDirectories(runDir);
            objectMapper.writeValue(runJson.toFile(), evidence);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write fuzz run evidence", e);
        }
    }
}
