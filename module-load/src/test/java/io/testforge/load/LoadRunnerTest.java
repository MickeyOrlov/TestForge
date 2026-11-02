package io.testforge.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testforge.artifact.ArtifactSink;
import io.testforge.artifact.TestArtifact;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoadRunnerTest {
    @TempDir Path dir;
    static final String SUMMARY = """
            {"metrics": {
              "iterations": {"values": {"count": 3}},
              "http_reqs": {"values": {"count": 6, "rate": 2.5}},
              "http_req_duration": {"values": {"p(95)": 25.0}, "thresholds": {"p(95)<500": {"ok": true}}},
              "http_req_failed": {"values": {"rate": 0}},
              "dropped_iterations": {"values": {"count": 0}}},
             "root_group": {"name": "a-secret-that-must-not-be-published"}}
            """;

    private LoadProperties properties(boolean enabled, boolean html) throws IOException {
        Path script = dir.resolve("scenario with spaces.js");
        Files.writeString(script, "export default function () {}\n");
        return new LoadProperties(enabled, "k6", null, script.toString(), "https://staging.example.test",
                dir.resolve("output").toString(), Duration.ofSeconds(3), Map.of("API_TOKEN", "top-secret"), html);
    }

    private static LoadProcess stub(String summary, int exitCode, boolean html) {
        return (command, cwd, env, timeout) -> {
            if (command.contains("version")) {
                return new LoadProcess.Result(0, false, "k6 v2.3.0 (test)\n");
            }
            if (summary != null) {
                Files.writeString(Path.of(env.get("TESTFORGE_LOAD_SUMMARY")), summary);
            }
            if (html) {
                Files.writeString(Path.of(env.get("K6_WEB_DASHBOARD_EXPORT")), "<html>k6</html>");
            }
            return new LoadProcess.Result(exitCode, false, "raw-output-secret");
        };
    }

    @Test void nativeResultsArePublishedWithoutRawOutputEnvironmentOrNames() throws IOException {
        List<TestArtifact> artifacts = new ArrayList<>();
        ArtifactSink sink = new ArtifactSink() {
            @Override public Path directoryFor(String source) { return dir; }
            @Override public void register(TestArtifact artifact) { artifacts.add(artifact); }
            @Override public TestArtifact write(String s, String c, String n, String m, String text) {
                throw new AssertionError("Runner owns its output directory");
            }
        };
        LoadRunner runner = new LoadRunner(properties(true, true), stub(SUMMARY, 0, true), sink);
        LoadReport first = runner.assertHealthy();
        LoadReport second = runner.assertHealthy();

        assertThat(first.outcome()).isEqualTo(LoadReport.Outcome.PASSED);
        assertThat(first.metrics()).isEqualTo(new LoadReport.Metrics(3, 6, 2.5, 25.0, 0.0, 0, 1, 0));
        assertThat(first.scriptSha256()).hasSize(64);
        assertThat(first.directory()).isNotEqualTo(second.directory());
        assertThat(artifacts).hasSize(6);
        assertThat(Path.of(first.directory()).resolve("k6-summary.raw.json")).doesNotExist();
        for (String file : List.of("report.md", "report.json")) {
            assertThat(Files.readString(Path.of(first.directory()).resolve(file)))
                    .doesNotContain("top-secret", "API_TOKEN", "raw-output-secret", "a-secret-that-must-not-be-published",
                            "staging.example.test");
        }
    }

    @Test void thresholdsAreNativeAndFailuresAreNeverAPass() throws IOException {
        String failed = SUMMARY.replace("\"ok\": true", "\"ok\": false");
        LoadRunner runner = new LoadRunner(properties(true, false), stub(failed, 99, false), null);
        assertThat(runner.run().outcome()).isEqualTo(LoadReport.Outcome.THRESHOLDS_FAILED);
        assertThatThrownBy(runner::assertHealthy).isInstanceOf(LoadException.class)
                .hasMessageContaining("THRESHOLDS_FAILED");
        assertThat(new LoadRunner(properties(true, false), stub(SUMMARY, 99, false), null).run().outcome())
                .isEqualTo(LoadReport.Outcome.INVALID_RESULT);
    }

    @Test void droppedIterationsDoNotHideFailedThresholds() throws IOException {
        String summary = SUMMARY.replace("\"ok\": true", "\"ok\": false").replace("\"count\": 0", "\"count\": 5");
        LoadReport report = new LoadRunner(properties(true, false), stub(summary, 99, false), null).run();
        assertThat(report.outcome()).isEqualTo(LoadReport.Outcome.THRESHOLDS_FAILED);
        assertThat(report.detail()).contains("thresholds failed", "dropped iterations");
        assertThat(report.metrics().droppedIterations()).isEqualTo(5);
        assertThat(report.metrics().failedThresholds()).isEqualTo(1);
    }

    @Test void extraPrivateSummaryFilesAreRemovedWithoutLosingTheVerdict() throws IOException {
        for (int exit : new int[] {0, 99}) {
            Path[] privateDirectory = new Path[1];
            LoadProcess extra = (command, cwd, env, timeout) -> {
                if (command.contains("version")) return new LoadProcess.Result(0, false, "k6 v2.3.0 (fixture)");
                Path summary = Path.of(env.get("TESTFORGE_LOAD_SUMMARY"));
                privateDirectory[0] = summary.getParent();
                Files.createDirectories(summary.getParent().resolve("extra/nested"));
                Files.writeString(summary.getParent().resolve("extra/nested/secret.json"), "private-secret");
                Files.writeString(summary, exit == 99 ? SUMMARY.replace("\"ok\": true", "\"ok\": false") : SUMMARY);
                return new LoadProcess.Result(exit, false, "");
            };
            LoadReport report = new LoadRunner(properties(true, false), extra, null).run();
            assertThat(report.outcome()).isEqualTo(exit == 99 ? LoadReport.Outcome.THRESHOLDS_FAILED : LoadReport.Outcome.PASSED);
            assertThat(privateDirectory[0]).doesNotExist();
            assertThat(Path.of(report.directory()).resolve("report.md")).exists();
            assertThat(Files.readString(Path.of(report.directory()).resolve("report.json"))).doesNotContain("private-secret");
        }
    }

    @Test void cleanupDoesNotFollowSymlinksOutsideThePrivateDirectory() throws IOException {
        Path outside = dir.resolve("outside.json");
        Files.writeString(outside, "keep");
        Path temporary = Files.createDirectory(dir.resolve("private"));
        try {
            Files.createSymbolicLink(temporary.resolve("link"), outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            org.junit.jupiter.api.Assumptions.abort("Symlinks unavailable on this platform");
        }
        assertThat(LoadRunner.removePrivateSummary(temporary)).isTrue();
        assertThat(Files.readString(outside)).isEqualTo("keep");
    }

    @Test void failedCleanupStillPublishesThresholdVerdictAndInvalidatesAnOtherwisePassingRun() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileStore(dir).supportsFileAttributeView("posix"));
        for (int exit : new int[] {0, 99}) {
            Path[] temporary = new Path[1];
            Path[] locked = new Path[1];
            LoadProcess unremovable = (command, cwd, env, timeout) -> {
                if (command.contains("version")) return new LoadProcess.Result(0, false, "k6 v2.3.0 (fixture)");
                Path summary = Path.of(env.get("TESTFORGE_LOAD_SUMMARY"));
                temporary[0] = summary.getParent();
                Files.writeString(summary, exit == 99 ? SUMMARY.replace("\"ok\": true", "\"ok\": false") : SUMMARY);
                locked[0] = Files.createDirectory(summary.getParent().resolve("locked"));
                Files.writeString(locked[0].resolve("private.json"), "private-secret");
                Files.setPosixFilePermissions(locked[0], java.util.Set.of());
                org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(locked[0]), "Requires permission enforcement");
                return new LoadProcess.Result(exit, false, "");
            };
            try {
                LoadReport report = new LoadRunner(properties(true, false), unremovable, null).run();
                assertThat(report.outcome()).isEqualTo(exit == 99 ? LoadReport.Outcome.THRESHOLDS_FAILED
                        : LoadReport.Outcome.INVALID_RESULT);
                assertThat(report.detail()).contains("cleanup failed");
                assertThat(report.metrics()).isNotNull();
                assertThat(Path.of(report.directory()).resolve("report.md")).exists();
                assertThat(Files.readString(Path.of(report.directory()).resolve("report.json"))).doesNotContain("private-secret");
            } finally {
                if (locked[0] != null) Files.setPosixFilePermissions(locked[0],
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
                if (temporary[0] != null) LoadRunner.removePrivateSummary(temporary[0]);
            }
        }
    }

    @Test void missingMalformedEmptyAndDroppedResultsCannotPass() throws IOException {
        for (String input : new String[] {null, "{}", "   ", "null", "not-json-secret", SUMMARY.replace("\"count\": 3", "\"count\": 0"),
                SUMMARY.replace("\"count\": 0", "\"count\": 5"),
                SUMMARY.replace(", \"thresholds\": {\"p(95)<500\": {\"ok\": true}}", "")}) {
            LoadReport report = new LoadRunner(properties(true, false), stub(input, 0, false), null).run();
            assertThat(report.outcome()).as(String.valueOf(input)).isEqualTo(LoadReport.Outcome.INVALID_RESULT);
            assertThat(Files.readString(Path.of(report.directory()).resolve("report.json")))
                    .doesNotContain("not-json-secret");
        }
        assertThat(new LoadRunner(properties(true, true), stub(SUMMARY, 0, false), null).run().outcome())
                .as("requested HTML must exist").isEqualTo(LoadReport.Outcome.INVALID_RESULT);
    }

    @Test void abnormalExitAndTimeoutDoNotBecomeThresholdFailures() throws IOException {
        assertThat(new LoadRunner(properties(true, false), stub(SUMMARY, 107, false), null).run().outcome())
                .isEqualTo(LoadReport.Outcome.EXECUTION_ERROR);
        LoadProcess timedOut = (command, cwd, env, timeout) -> command.contains("version")
                ? new LoadProcess.Result(0, false, "k6 v2.3.0 (test)")
                : new LoadProcess.Result(-1, true, "secret");
        assertThat(new LoadRunner(properties(true, false), timedOut, null).run().outcome())
                .isEqualTo(LoadReport.Outcome.TIMED_OUT);
    }

    @Test void cancellationIsReportedAndInterruptFlagRestored() throws IOException {
        LoadProcess cancelled = (command, cwd, env, timeout) -> { throw new InterruptedException(); };
        try {
            LoadReport report = new LoadRunner(properties(true, false), cancelled, null).run();
            assertThat(report.outcome()).isEqualTo(LoadReport.Outcome.CANCELLED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test void disabledMissingScriptAndVersionMismatchNeverStartARun() throws IOException {
        LoadProcess never = (command, cwd, env, timeout) -> { throw new AssertionError("No launch expected"); };
        assertThatThrownBy(() -> new LoadRunner(properties(false, false), never, null).run())
                .isInstanceOf(LoadException.class).hasMessageContaining("enabled=true");
        LoadProperties configured = properties(true, false);
        Files.delete(Path.of(configured.script()));
        assertThatThrownBy(() -> new LoadRunner(configured, never, null).run()).isInstanceOf(LoadException.class);
        LoadProcess wrong = (command, cwd, env, timeout) -> {
            assertThat(command).containsExactly("k6", "version");
            return new LoadProcess.Result(0, false, "k6 v1.0.0 (unsupported)");
        };
        assertThat(new LoadRunner(properties(true, false), wrong, null).run().outcome())
                .isEqualTo(LoadReport.Outcome.EXECUTION_ERROR);
    }

    @Test void credentialsAreOnlyInEnvironmentAndPathsAreSingleArguments() throws IOException {
        LoadProcess delegate = stub(SUMMARY, 0, false);
        LoadProperties configured = properties(true, false);
        LoadProcess inspecting = (command, cwd, env, timeout) -> {
            assertThat(command.toString()).doesNotContain("top-secret", "staging.example.test");
            assertThat(env).containsEntry("API_TOKEN", "top-secret")
                    .containsEntry("K6_WEB_DASHBOARD_PORT", "-1");
            if (command.contains("run")) {
                assertThat(command.getLast()).isEqualTo(configured.script());
                assertThat(Files.readString(Path.of(command.get(command.indexOf("--config") + 1))))
                        .isEqualTo("{}");
            }
            return delegate.run(command, cwd, env, timeout);
        };
        new LoadRunner(configured, inspecting, null).assertHealthy();
        assertThat(configured.toString()).doesNotContain("top-secret");
    }
}
