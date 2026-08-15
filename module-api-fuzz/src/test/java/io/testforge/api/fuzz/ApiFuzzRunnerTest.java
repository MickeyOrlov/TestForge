package io.testforge.api.fuzz;

import io.testforge.api.discovery.ApiDiscoveryProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiFuzzRunnerTest {

    @TempDir
    Path tempDir;

    private final ResourceLoader resourceLoader = new DefaultResourceLoader();
    private final NdjsonReportParser reportParser = new NdjsonReportParser();
    private final FuzzEvidenceWriter evidenceWriter = new FuzzEvidenceWriter();

    static class FakeProcessRunner implements ProcessRunner {
        private final List<List<String>> executedCommands = new ArrayList<>();
        private ProcessResult versionResult = new ProcessResult(0, "st, version 4.24.3\n", "", false);
        private ProcessResult runResult = new ProcessResult(0, "", "", false);
        private String ndjsonContentToWrite = null;
        private Path ndjsonWriteTarget = null;
        private Exception versionException = null;
        private String ndjsonInWorkingDir = null;

        public FakeProcessRunner withNdjsonInWorkingDir(String content) {
            this.ndjsonInWorkingDir = content;
            return this;
        }

        public FakeProcessRunner withRunResult(ProcessResult result) {
            this.runResult = result;
            return this;
        }

        public FakeProcessRunner withNdjson(Path targetDir, String content) {
            this.ndjsonWriteTarget = targetDir;
            this.ndjsonContentToWrite = content;
            return this;
        }

        public FakeProcessRunner withVersionException(Exception e) {
            this.versionException = e;
            return this;
        }

        public List<List<String>> executedCommands() {
            return executedCommands;
        }

        @Override
        public ProcessResult run(List<String> command, Path workingDir, Map<String, String> extraEnv, Duration timeout) {
            executedCommands.add(command);
            if (command.contains("--version")) {
                if (versionException != null) {
                    if (versionException instanceof RuntimeException re) throw re;
                    throw new RuntimeException(versionException);
                }
                return versionResult;
            }
            if (ndjsonInWorkingDir != null && workingDir != null) {
                try {
                    Files.createDirectories(workingDir);
                    Files.writeString(workingDir.resolve("report.ndjson"), ndjsonInWorkingDir);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            if (ndjsonContentToWrite != null && ndjsonWriteTarget != null) {
                try {
                    Files.createDirectories(ndjsonWriteTarget);
                    Path reportFile = ndjsonWriteTarget.resolve("report.ndjson");
                    Files.writeString(reportFile, ndjsonContentToWrite);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return runResult;
        }
    }

    @Test
    void enabledFalsePerformsZeroProcessExecutions() {
        ApiFuzzProperties props = new ApiFuzzProperties(
                false, tempDir.toString(), List.of("demo"), "http://localhost:8080",
                null, false, null, null, null, null, null, null, "st", null,
                null);
        FakeProcessRunner fakeRunner = new FakeProcessRunner();
        SchemathesisExecutor executor = new SchemathesisExecutor(fakeRunner);
        ApiDiscoveryProperties discoveryProps = new ApiDiscoveryProperties(null, null, null, null, null, Map.of(
                "demo", new ApiDiscoveryProperties.Spec("classpath:/openapi/demo.yaml")
        ));
        FuzzSpecMaterializer materializer = new FuzzSpecMaterializer(discoveryProps, resourceLoader, tempDir);
        ApiFuzzRunner runner = new ApiFuzzRunner(materializer, executor, reportParser, evidenceWriter, discoveryProps, props);

        ApiFuzzReport report = runner.run();

        assertThat(fakeRunner.executedCommands()).isEmpty();
        assertThat(report.outcome()).isEqualTo(ApiFuzzOutcome.PASSED);
    }

    @Test
    void noConfiguredSpecsPerformsZeroProcessExecutions() {
        ApiDiscoveryProperties emptyDiscoveryProps = new ApiDiscoveryProperties(null, null, null, null, null, Map.of());
        ApiFuzzProperties props = new ApiFuzzProperties(
                true, tempDir.toString(), List.of(), "http://localhost:8080",
                null, false, null, null, null, null, null, null, "st", null,
                null);
        FakeProcessRunner fakeRunner = new FakeProcessRunner();
        SchemathesisExecutor executor = new SchemathesisExecutor(fakeRunner);
        FuzzSpecMaterializer materializer = new FuzzSpecMaterializer(emptyDiscoveryProps, resourceLoader, tempDir);
        ApiFuzzRunner runner = new ApiFuzzRunner(materializer, executor, reportParser, evidenceWriter, emptyDiscoveryProps, props);

        ApiFuzzReport report = runner.run();

        assertThat(fakeRunner.executedCommands()).isEmpty();
        assertThat(report.outcome()).isEqualTo(ApiFuzzOutcome.PASSED);
    }

    @Test
    void cleanRunYieldsPassed() throws Exception {
        Path specFile = tempDir.resolve("demo.yaml");
        Files.writeString(specFile, "openapi: 3.0.0\n");
        ApiDiscoveryProperties discoveryProps = new ApiDiscoveryProperties(null, null, null, null, null, Map.of(
                "demo", new ApiDiscoveryProperties.Spec("file:" + specFile.toAbsolutePath())
        ));
        ApiFuzzProperties props = new ApiFuzzProperties(
                true, tempDir.toString(), List.of("demo"), "http://localhost:8080",
                null, false, null, 12345L, null, null, null, null, "st", null,
                null);

        String cleanNdjson = """
                {"Initialize": {"schemathesis_version": "4.24.3", "seed": 12345}}
                {"EngineFinished": {"running_time": 0.5}}
                """;

        FakeProcessRunner fakeRunner = new FakeProcessRunner()
                .withNdjson(tempDir.resolve("demo"), cleanNdjson);
        SchemathesisExecutor executor = new SchemathesisExecutor(fakeRunner);
        FuzzSpecMaterializer materializer = new FuzzSpecMaterializer(discoveryProps, resourceLoader, tempDir);
        ApiFuzzRunner runner = new ApiFuzzRunner(materializer, executor, reportParser, evidenceWriter, discoveryProps, props);

        ApiFuzzReport report = runner.run();

        assertThat(report.outcome()).isEqualTo(ApiFuzzOutcome.PASSED);
        assertThat(report.hasFindings()).isFalse();
        assertThat(report.findings()).isEmpty();
        assertThat(runner.assertHealthy()).isNotNull();
    }

    @Test
    void runWithFailingCheckYieldsFindingsAndListsThem() throws Exception {
        Path specFile = tempDir.resolve("demo.yaml");
        Files.writeString(specFile, "openapi: 3.0.0\n");
        ApiDiscoveryProperties discoveryProps = new ApiDiscoveryProperties(null, null, null, null, null, Map.of(
                "demo", new ApiDiscoveryProperties.Spec("file:" + specFile.toAbsolutePath())
        ));
        ApiFuzzProperties props = new ApiFuzzProperties(
                true, tempDir.toString(), List.of("demo"), "http://localhost:8080",
                null, false, null, 12345L, null, null, null, null, "st", null,
                null);

        String findingsNdjson = """
                {"Initialize": {"schemathesis_version": "4.24.3", "seed": 12345}}
                {"ScenarioFinished": {"status": "failure", "phase": "fuzzing", "recorder": {"label": "GET /test", "cases": {"c1": {"value": {"method": "GET", "path": "/test"}}}, "checks": {"c1": [{"name": "not_a_server_error", "status": "failure", "failure_info": {"failure": {"message": "500 Internal Server Error"}}}]}}}}
                """;

        FakeProcessRunner fakeRunner = new FakeProcessRunner()
                .withNdjson(tempDir.resolve("demo"), findingsNdjson);
        SchemathesisExecutor executor = new SchemathesisExecutor(fakeRunner);
        FuzzSpecMaterializer materializer = new FuzzSpecMaterializer(discoveryProps, resourceLoader, tempDir);
        ApiFuzzRunner runner = new ApiFuzzRunner(materializer, executor, reportParser, evidenceWriter, discoveryProps, props);

        ApiFuzzReport report = runner.run();

        assertThat(report.outcome()).isEqualTo(ApiFuzzOutcome.FINDINGS);
        assertThat(report.hasFindings()).isTrue();
        assertThat(report.findings()).hasSize(1);
        assertThat(report.findings().get(0).checkName()).isEqualTo("not_a_server_error");
        assertThat(report.findings().get(0).message()).isEqualTo("500 Internal Server Error");

        // Default assertHealthy does not throw on findings
        assertThat(runner.assertHealthy()).isNotNull();

        // assertHealthy(true) throws on findings
        assertThatThrownBy(() -> runner.assertHealthy(true))
                .isInstanceOf(ApiFuzzException.class)
                .hasMessageContaining("findings");
    }

    @Test
    void runWithNonFatalErrorYieldsExecutionError() throws Exception {
        Path specFile = tempDir.resolve("demo.yaml");
        Files.writeString(specFile, "openapi: 3.0.0\n");
        ApiDiscoveryProperties discoveryProps = new ApiDiscoveryProperties(null, null, null, null, null, Map.of(
                "demo", new ApiDiscoveryProperties.Spec("file:" + specFile.toAbsolutePath())
        ));
        ApiFuzzProperties props = new ApiFuzzProperties(
                true, tempDir.toString(), List.of("demo"), "http://localhost:8080",
                null, false, null, 12345L, null, null, null, null, "st", null,
                null);

        String errorNdjson = """
                {"Initialize": {"schemathesis_version": "4.24.3", "seed": 12345}}
                {"NonFatalError": {"message": "Connection refused"}}
                """;

        FakeProcessRunner fakeRunner = new FakeProcessRunner()
                .withNdjson(tempDir.resolve("demo"), errorNdjson);
        SchemathesisExecutor executor = new SchemathesisExecutor(fakeRunner);
        FuzzSpecMaterializer materializer = new FuzzSpecMaterializer(discoveryProps, resourceLoader, tempDir);
        ApiFuzzRunner runner = new ApiFuzzRunner(materializer, executor, reportParser, evidenceWriter, discoveryProps, props);

        ApiFuzzReport report = runner.run();

        assertThat(report.outcome()).isEqualTo(ApiFuzzOutcome.EXECUTION_ERROR);
        assertThatThrownBy(runner::assertHealthy)
                .isInstanceOf(ApiFuzzException.class);
    }

    @Test
    void timeoutYieldsExecutionError() throws Exception {
        Path specFile = tempDir.resolve("demo.yaml");
        Files.writeString(specFile, "openapi: 3.0.0\n");
        ApiDiscoveryProperties discoveryProps = new ApiDiscoveryProperties(null, null, null, null, null, Map.of(
                "demo", new ApiDiscoveryProperties.Spec("file:" + specFile.toAbsolutePath())
        ));
        ApiFuzzProperties props = new ApiFuzzProperties(
                true, tempDir.toString(), List.of("demo"), "http://localhost:8080",
                null, false, null, 12345L, null, null, null, null, "st", null,
                null);

        FakeProcessRunner fakeRunner = new FakeProcessRunner()
                .withRunResult(new ProcessResult(-1, "", "", true));
        SchemathesisExecutor executor = new SchemathesisExecutor(fakeRunner);
        FuzzSpecMaterializer materializer = new FuzzSpecMaterializer(discoveryProps, resourceLoader, tempDir);
        ApiFuzzRunner runner = new ApiFuzzRunner(materializer, executor, reportParser, evidenceWriter, discoveryProps, props);

        ApiFuzzReport report = runner.run();

        assertThat(report.outcome()).isEqualTo(ApiFuzzOutcome.EXECUTION_ERROR);
        assertThat(report.errors()).anyMatch(err -> err.contains("timed out"));
        assertThatThrownBy(runner::assertHealthy)
                .isInstanceOf(ApiFuzzException.class);
    }

    @Test
    void missingReportFileYieldsExecutionError() throws Exception {
        Path specFile = tempDir.resolve("demo.yaml");
        Files.writeString(specFile, "openapi: 3.0.0\n");
        ApiDiscoveryProperties discoveryProps = new ApiDiscoveryProperties(null, null, null, null, null, Map.of(
                "demo", new ApiDiscoveryProperties.Spec("file:" + specFile.toAbsolutePath())
        ));
        ApiFuzzProperties props = new ApiFuzzProperties(
                true, tempDir.toString(), List.of("demo"), "http://localhost:8080",
                null, false, null, 12345L, null, null, null, null, "st", null,
                null);

        FakeProcessRunner fakeRunner = new FakeProcessRunner(); // no NDJSON created
        SchemathesisExecutor executor = new SchemathesisExecutor(fakeRunner);
        FuzzSpecMaterializer materializer = new FuzzSpecMaterializer(discoveryProps, resourceLoader, tempDir);
        ApiFuzzRunner runner = new ApiFuzzRunner(materializer, executor, reportParser, evidenceWriter, discoveryProps, props);

        ApiFuzzReport report = runner.run();

        assertThat(report.outcome()).isEqualTo(ApiFuzzOutcome.EXECUTION_ERROR);
        assertThat(report.errors()).anyMatch(err -> err.contains("missing"));
        assertThatThrownBy(runner::assertHealthy)
                .isInstanceOf(ApiFuzzException.class);
    }

    @Test
    void seedIsRecordedInEvidenceEvenWhenAutoGenerated() throws Exception {
        Path specFile = tempDir.resolve("demo.yaml");
        Files.writeString(specFile, "openapi: 3.0.0\n");
        ApiDiscoveryProperties discoveryProps = new ApiDiscoveryProperties(null, null, null, null, null, Map.of(
                "demo", new ApiDiscoveryProperties.Spec("file:" + specFile.toAbsolutePath())
        ));
        ApiFuzzProperties props = new ApiFuzzProperties(
                true, tempDir.toString(), List.of("demo"), "http://localhost:8080",
                null, false, null, null, null, null, null, null, "st", null,
                null);

        String cleanNdjson = """
                {"Initialize": {"schemathesis_version": "4.24.3", "seed": 999}}
                {"EngineFinished": {"running_time": 0.5}}
                """;

        FakeProcessRunner fakeRunner = new FakeProcessRunner()
                .withNdjson(tempDir.resolve("demo"), cleanNdjson);
        SchemathesisExecutor executor = new SchemathesisExecutor(fakeRunner);
        FuzzSpecMaterializer materializer = new FuzzSpecMaterializer(discoveryProps, resourceLoader, tempDir);
        ApiFuzzRunner runner = new ApiFuzzRunner(materializer, executor, reportParser, evidenceWriter, discoveryProps, props);

        ApiFuzzReport report = runner.run();

        assertThat(report.seed()).isNotNull();
        assertThat(report.seed()).isGreaterThan(0L);

        // Evidence is written per spec, so each spec keeps its own record.
        Path runJson = tempDir.resolve(report.runId()).resolve("demo").resolve("run.json");
        assertThat(Files.exists(runJson)).isTrue();
        String runJsonContent = Files.readString(runJson);
        assertThat(runJsonContent).contains("\"seed\" : " + report.seed());
    }

    @Test
    void exitCode2YieldsConfigurationError() throws Exception {
        Path specFile = tempDir.resolve("demo.yaml");
        Files.writeString(specFile, "openapi: 3.0.0\n");
        ApiDiscoveryProperties discoveryProps = new ApiDiscoveryProperties(null, null, null, null, null, Map.of(
                "demo", new ApiDiscoveryProperties.Spec("file:" + specFile.toAbsolutePath())
        ));
        ApiFuzzProperties props = new ApiFuzzProperties(
                true, tempDir.toString(), List.of("demo"), "http://localhost:8080",
                null, false, null, 12345L, null, null, null, null, "st", null,
                null);

        FakeProcessRunner fakeRunner = new FakeProcessRunner()
                .withRunResult(new ProcessResult(2, "", "invalid flag", false));
        SchemathesisExecutor executor = new SchemathesisExecutor(fakeRunner);
        FuzzSpecMaterializer materializer = new FuzzSpecMaterializer(discoveryProps, resourceLoader, tempDir);
        ApiFuzzRunner runner = new ApiFuzzRunner(materializer, executor, reportParser, evidenceWriter, discoveryProps, props);

        ApiFuzzReport report = runner.run();

        assertThat(report.outcome()).isEqualTo(ApiFuzzOutcome.CONFIGURATION_ERROR);
        assertThatThrownBy(runner::assertHealthy)
                .isInstanceOf(ApiFuzzException.class);
    }

    @Test
    void eachSpecKeepsItsOwnEvidenceAndArtifacts() throws Exception {
        // Two regressions in one: evidence keyed only on the run id meant the
        // second spec overwrote the first, and copying the aggregate artifact
        // map into every record meant spec B's evidence listed spec A's files.
        String cleanNdjson = """
                {"Initialize": {"command": "st run", "schemathesis_version": "4.24.3", "seed": 7}}
                {"ScenarioFinished": {"status": "success", "phase": "Fuzzing", "recorder": {"label": "GET /x", "cases": {}, "checks": {}}}}
                {"EngineFinished": {"running_time": 0.1}}
                """;

        ApiFuzzProperties props = new ApiFuzzProperties(
                true, tempDir.toString(), List.of("alpha", "beta"), "http://localhost:8080",
                null, false, null, 7L, null, null, null, null, "st", null,
                null);
        FakeProcessRunner fakeRunner = new FakeProcessRunner().withNdjsonInWorkingDir(cleanNdjson);
        SchemathesisExecutor executor = new SchemathesisExecutor(fakeRunner);
        ApiDiscoveryProperties discoveryProps = new ApiDiscoveryProperties(null, null, null, null, null, Map.of(
                "alpha", new ApiDiscoveryProperties.Spec("classpath:/openapi/demo.yaml"),
                "beta", new ApiDiscoveryProperties.Spec("classpath:/openapi/demo.yaml")
        ));
        FuzzSpecMaterializer materializer = new FuzzSpecMaterializer(discoveryProps, resourceLoader, tempDir);
        ApiFuzzRunner runner = new ApiFuzzRunner(materializer, executor, reportParser, evidenceWriter, discoveryProps, props);

        ApiFuzzReport report = runner.run();

        Path alpha = tempDir.resolve(report.runId()).resolve("alpha").resolve("run.json");
        Path beta = tempDir.resolve(report.runId()).resolve("beta").resolve("run.json");
        assertThat(alpha).exists();
        assertThat(beta).exists();

        // Each record must describe only its own spec.
        assertThat(Files.readString(alpha)).contains("\"specId\" : \"alpha\"").doesNotContain("beta");
        assertThat(Files.readString(beta)).contains("\"specId\" : \"beta\"").doesNotContain("alpha");

        // The version probe runs once for the whole run, not once per spec.
        assertThat(fakeRunner.executedCommands().stream()
                .filter(c -> c.contains("--version")).count()).isEqualTo(1L);
    }
}
