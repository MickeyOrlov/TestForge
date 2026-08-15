package io.testforge.api.fuzz;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FuzzEvidenceTest {

    @Test
    void redactsCredentialsFromTargetUrl() {
        FuzzRunEvidence evidence = createEvidence("https://user:supersecret@api.example.test/v1");
        assertThat(evidence.targetUrl()).isEqualTo("https://api.example.test/v1");
    }

    @Test
    void handlesMalformedUrlWithoutThrowing() {
        FuzzRunEvidence evidence = createEvidence("https://api.example.com:8080/path with space?query=1");
        assertThat(evidence.targetUrl()).isEqualTo("<redacted-malformed-url>");
    }

    @Test
    void writesEvidenceAndPersistsSeed(@TempDir Path tempDir) throws Exception {
        FuzzRunEvidence evidence = createEvidence("https://user:password@api.example.test/v1");
        FuzzEvidenceWriter writer = new FuzzEvidenceWriter();
        
        writer.writeEvidence(tempDir, evidence);
        
        Path jsonFile = tempDir.resolve(evidence.runId()).resolve(evidence.specId()).resolve("run.json");
        assertThat(jsonFile).exists();
        
        String jsonContent = Files.readString(jsonFile);
        
        // Assert the secret string does NOT appear anywhere in the serialised JSON
        assertThat(jsonContent).doesNotContain("password");
        assertThat(jsonContent).doesNotContain("user");
        
        // Assert the seed is persisted and read back
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        FuzzRunEvidence readBack = mapper.readValue(jsonFile.toFile(), FuzzRunEvidence.class);
        assertThat(readBack.seed()).isEqualTo(42L);
        
        // The written JSON contains schemathesisVersion, phases and the effective methods
        assertThat(jsonContent).contains("\"schemathesisVersion\" : \"3.37.0\"");
        assertThat(jsonContent).contains("\"phases\"");
        assertThat(jsonContent).contains("\"generate\"");
        assertThat(jsonContent).contains("\"methods\"");
        assertThat(jsonContent).contains("\"GET\"");
    }

    private FuzzRunEvidence createEvidence(String url) {
        return new FuzzRunEvidence(
                "run-123",
                "spec-456",
                "openapi.yaml",
                url,
                "3.37.0",
                42L,
                List.of("generate", "fuzz"),
                Set.of("GET", "POST"),
                "positive",
                100,
                false,
                1,
                "Failed",
                Map.of("report", "report.json"),
                Instant.parse("2026-08-15T16:00:00Z"),
                Duration.ofMinutes(1)
        );
    }

    @Test
    void eachSpecGetsItsOwnEvidenceFile(@TempDir Path tempDir) {
        // One run fuzzes every configured spec. Keying the path on the run id
        // alone made each spec overwrite the previous one's evidence, leaving
        // only the last -- the reproducibility promise silently broken.
        FuzzEvidenceWriter writer = new FuzzEvidenceWriter();
        FuzzRunEvidence a = createEvidence("https://api.example.test/v1");
        FuzzRunEvidence b = new FuzzRunEvidence(
                a.runId(), "spec-b", a.specLocation(), a.targetUrl(), a.schemathesisVersion(),
                a.seed(), a.phases(), a.methods(), a.generationMode(), a.maxExamples(),
                a.allowUnsafeMethods(), a.exitCode(), a.outcome(), a.artifacts(),
                a.startedAt(), a.duration());

        writer.writeEvidence(tempDir, a);
        writer.writeEvidence(tempDir, b);

        assertThat(tempDir.resolve(a.runId()).resolve(a.specId()).resolve("run.json")).exists();
        assertThat(tempDir.resolve(a.runId()).resolve("spec-b").resolve("run.json")).exists();
    }
}
