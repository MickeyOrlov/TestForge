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

    public void writeEvidence(Path outputDir, FuzzRunEvidence evidence) {
        Path runDir = outputDir.resolve(evidence.runId());
        Path runJson = runDir.resolve("run.json");
        try {
            Files.createDirectories(runDir);
            objectMapper.writeValue(runJson.toFile(), evidence);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write fuzz run evidence", e);
        }
    }
}
