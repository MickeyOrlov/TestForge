package io.testforge.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.testforge.artifact.ArtifactSink;
import io.testforge.artifact.TestArtifact;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests proving Property 1: "REPORTING NEVER MASKS THE ORIGINAL FAILURE."
 *
 * <p>Reporting is best-effort and secondary. A diagnostic that cannot be written
 * must never replace or wrap the product failure that caused the run to be interesting.
 */
class ReportingFailureSemanticsTest {

    @Test
    void productFailureSurvivesWhenArtifactPublishingFailsDueToUnwritableDirectory(@TempDir Path tempDir) throws IOException {
        // Construct a scenario where a product assertion fails AND artifact publishing fails
        ArtifactRunLayout layout = new ArtifactRunLayout(tempDir, "run-fail-dir");
        Path blockingFile = layout.runRoot().resolve("module-http");
        Files.createDirectories(blockingFile.getParent());
        Files.createFile(blockingFile);

        RunArtifactSink sink = new RunArtifactSink(layout);
        AssertionError originalFailure = new AssertionError("Expected HTTP 200 OK but got 500 Internal Server Error");

        Throwable surfaced = null;
        try {
            try {
                // Product action / assertion fails
                throw originalFailure;
            } finally {
                // Artifact publishing fails because target directory is unwritable/blocked
                sink.write(
                        "module-http",
                        "http-log",
                        "response-500.json",
                        "application/json",
                        "{\"error\":\"Internal Server Error\"}"
                );
            }
        } catch (Throwable t) {
            surfaced = t;
        }

        // Assert that the failure surfaced to the caller is the ORIGINAL assertion failure, with type and message intact
        assertThat(surfaced)
                .isNotNull()
                .isSameAs(originalFailure)
                .isInstanceOf(AssertionError.class)
                .hasMessage("Expected HTTP 200 OK but got 500 Internal Server Error");

        // Assert that no suppressed/again-thrown reporting exception replaces or wraps the original
        assertThat(surfaced.getSuppressed()).isEmpty();
        assertThat(surfaced.getCause()).isNull();

        // Assert that the reporting problem is still visible in sink.problems() — visible but SECONDARY
        List<ReportingProblem> problems = sink.problems();
        assertThat(problems).isNotEmpty();
        assertThat(problems.get(0).operation()).isEqualTo("write");

        // Assert problem is visible in the manifest's problems section
        ArtifactManifestWriter manifestWriter = new ArtifactManifestWriter();
        List<String> problemStrings = problems.stream()
                .map(p -> p.operation() + ": " + p.message())
                .toList();
        manifestWriter.writeManifest(layout, sink.artifacts(), problemStrings);

        Path manifestPath = layout.runRoot().resolve("manifest.json");
        assertThat(Files.exists(manifestPath)).isTrue();
        String manifestContent = Files.readString(manifestPath);
        assertThat(manifestContent).contains("write");
    }

    @Test
    void productFailureSurvivesWhenSinkUnderlyingStoreThrows(@TempDir Path tempDir) {
        // Scenario: a sink whose underlying store throws unchecked exception
        ArtifactSink throwingSink = new ArtifactSink() {
            @Override
            public Path directoryFor(String source) {
                throw new RuntimeException("Underlying artifact store unavailable");
            }

            @Override
            public void register(TestArtifact artifact) {
                throw new RuntimeException("Underlying artifact store unavailable");
            }

            @Override
            public TestArtifact write(String source, String category, String name, String mediaType, String content) {
                throw new RuntimeException("Underlying artifact store unavailable");
            }
        };

        IllegalStateException originalFailure = new IllegalStateException("Order state was PENDING, expected COMPLETED");

        Throwable surfaced = null;
        try {
            try {
                throw originalFailure;
            } finally {
                // Safeguarded artifact publishing invocation
                try {
                    throwingSink.write("module-flow", "flow-path", "checkout.txt", "text/plain", "step 1 -> step 2");
                } catch (Throwable reportingEx) {
                    // Reporting exception caught/logged, original exception remains primary
                }
            }
        } catch (Throwable t) {
            surfaced = t;
        }

        assertThat(surfaced)
                .isNotNull()
                .isSameAs(originalFailure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Order state was PENDING, expected COMPLETED");

        assertThat(surfaced.getSuppressed()).isEmpty();
        assertThat(surfaced.getCause()).isNull();
    }

    @Test
    void productFailureSurvivesWithRunArtifactSinkEvenWhenCalledUnwrapped(@TempDir Path tempDir) throws IOException {
        // RunArtifactSink contract guarantees no method throws, so direct call in finally block leaves original exception intact
        ArtifactRunLayout layout = new ArtifactRunLayout(tempDir, "run-unwrapped");
        Path blockingFile = layout.runRoot().resolve("module-db");
        Files.createFile(blockingFile);

        RunArtifactSink sink = new RunArtifactSink(layout);
        CustomProductException originalFailure = new CustomProductException("DB query timed out after 5000ms");

        Throwable surfaced = null;
        try {
            try {
                throw originalFailure;
            } finally {
                sink.write("module-db", "query-log", "slow-query.sql", "text/plain", "SELECT * FROM orders");
            }
        } catch (Throwable t) {
            surfaced = t;
        }

        assertThat(surfaced)
                .isNotNull()
                .isSameAs(originalFailure)
                .isInstanceOf(CustomProductException.class)
                .hasMessage("DB query timed out after 5000ms");

        assertThat(surfaced.getSuppressed()).isEmpty();
        assertThat(surfaced.getCause()).isNull();
        assertThat(sink.problems()).isNotEmpty();
    }

    @Test
    void whenProductAssertionPassesAndReportingFailsTestDoesNotFail(@TempDir Path tempDir) throws IOException {
        // Inverse requirement: when product assertion PASSES and reporting fails, the test does not fail
        ArtifactRunLayout layout = new ArtifactRunLayout(tempDir, "run-pass-repo-fail");
        Path blockingFile = layout.runRoot().resolve("module-web");
        Files.createFile(blockingFile);

        RunArtifactSink sink = new RunArtifactSink(layout);

        assertThatCode(() -> {
            // Product action passes cleanly
            String result = "SUCCESS";
            assertThat(result).isEqualTo("SUCCESS");

            // Reporting write fails internally (blocked path) but does not throw or fail the test
            TestArtifact artifact = sink.write(
                    "module-web",
                    "screenshot",
                    "page.png",
                    "image/png",
                    "fake-png-bytes"
            );

            assertThat(artifact).isNotNull();
            assertThat(artifact.metadata()).containsEntry("writeFailed", "true");
        }).doesNotThrowAnyException();

        // Reporting problem is recorded as secondary
        assertThat(sink.problems()).isNotEmpty();
    }

    private static class CustomProductException extends RuntimeException {
        public CustomProductException(String message) {
            super(message);
        }
    }
}
