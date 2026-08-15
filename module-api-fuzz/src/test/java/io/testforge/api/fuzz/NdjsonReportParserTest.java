package io.testforge.api.fuzz;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NdjsonReportParserTest {

    private final NdjsonReportParser parser = new NdjsonReportParser();

    @Test
    void cleanRunMapsToPassed(@TempDir Path tempDir) throws IOException {
        Path reportPath = tempDir.resolve("clean.ndjson");
        Files.writeString(reportPath, """
            {"Initialize": {"command": "st run spec.yaml", "schemathesis_version": "4.24.3", "seed": 99}}
            {"ScenarioStarted": {}}
            {"ScenarioFinished": {"status": "success", "recorder": {"label": "GET /items", "cases": {}, "checks": {}}}}
            {"EngineFinished": {"running_time": 0.16, "stop_reason": "completed"}}
            """);

        ApiFuzzReport report = parser.parse(reportPath);

        assertThat(report.outcome()).isEqualTo(ApiFuzzOutcome.PASSED);
        assertThat(report.schemathesisVersion()).isEqualTo("4.24.3");
        assertThat(report.seed()).isEqualTo(99L);
        assertThat(report.findings()).isEmpty();
        assertThat(report.hasFindings()).isFalse();
    }

    @Test
    void runWithFailingCheckMapsToFindings(@TempDir Path tempDir) throws IOException {
        Path reportPath = tempDir.resolve("findings.ndjson");
        Files.writeString(reportPath, """
            {"Initialize": {"command": "st run spec.yaml", "schemathesis_version": "4.24.3", "seed": 99}}
            {"ScenarioFinished": {"id": "1", "phase": "Coverage", "status": "failure", "recorder": {"label": "GET /items", "cases": {"M5VMYR": {"value": {"method": "TRACE", "path": "/items", "id": "M5VMYR"}}}, "checks": {"M5VMYR": [{"name": "not_a_server_error", "status": "success"}, {"name": "unsupported_method", "status": "failure", "failure_info": {"failure": {"type": "UnsupportedMethodResponse", "message": "Method not allowed"}}}]}}}}
            """);

        ApiFuzzReport report = parser.parse(reportPath);

        assertThat(report.outcome()).isEqualTo(ApiFuzzOutcome.FINDINGS);
        assertThat(report.findings()).hasSize(1);
        assertThat(report.hasFindings()).isTrue();

        ApiFuzzFinding finding = report.findings().get(0);
        assertThat(finding.operationLabel()).isEqualTo("GET /items");
        assertThat(finding.phase()).isEqualTo("Coverage");
        assertThat(finding.method()).isEqualTo("TRACE");
        assertThat(finding.path()).isEqualTo("/items");
        assertThat(finding.checkName()).isEqualTo("unsupported_method");
        assertThat(finding.message()).isEqualTo("Method not allowed");
    }

    @Test
    void runContainingNonFatalErrorMapsToExecutionError(@TempDir Path tempDir) throws IOException {
        Path reportPath = tempDir.resolve("error.ndjson");
        Files.writeString(reportPath, """
            {"Initialize": {"command": "st run spec.yaml", "schemathesis_version": "4.24.3", "seed": 99}}
            {"ScenarioFinished": {"id": "1", "phase": "Coverage", "status": "failure", "recorder": {"label": "GET /items", "cases": {"M5VMYR": {"value": {"method": "TRACE", "path": "/items", "id": "M5VMYR"}}}, "checks": {"M5VMYR": [{"name": "unsupported_method", "status": "failure", "failure_info": {"failure": {"type": "UnsupportedMethodResponse", "message": "Method not allowed"}}}]}}}}
            {"NonFatalError": {"error": "Some infrastructure failure"}}
            """);

        ApiFuzzReport report = parser.parse(reportPath);

        // Even with findings, NonFatalError forces EXECUTION_ERROR
        assertThat(report.outcome()).isEqualTo(ApiFuzzOutcome.EXECUTION_ERROR);
        // It still keeps findings though
        assertThat(report.findings()).hasSize(1);
    }

    @Test
    void unknownEventTypeIsIgnored(@TempDir Path tempDir) throws IOException {
        Path reportPath = tempDir.resolve("unknown.ndjson");
        Files.writeString(reportPath, """
            {"Initialize": {"command": "st run spec.yaml", "schemathesis_version": "4.24.3", "seed": 99}}
            {"NewShinyEvent": {"data": "future compat"}}
            {"ScenarioFinished": {"status": "success", "recorder": {"label": "GET /items"}}}
            """);

        ApiFuzzReport report = parser.parse(reportPath);

        assertThat(report.outcome()).isEqualTo(ApiFuzzOutcome.PASSED);
    }

    @Test
    void missingFileThrowsApiFuzzException(@TempDir Path tempDir) {
        Path reportPath = tempDir.resolve("missing.ndjson");

        assertThatThrownBy(() -> parser.parse(reportPath))
            .isInstanceOf(ApiFuzzException.class)
            .hasMessageContaining("missing")
            .hasMessageContaining("missing.ndjson");
    }

    @Test
    void emptyFileThrowsApiFuzzException(@TempDir Path tempDir) throws IOException {
        Path reportPath = tempDir.resolve("empty.ndjson");
        Files.createFile(reportPath);

        assertThatThrownBy(() -> parser.parse(reportPath))
            .isInstanceOf(ApiFuzzException.class)
            .hasMessageContaining("empty")
            .hasMessageContaining("empty.ndjson");
    }

    @Test
    void onlyBlankLinesThrowsApiFuzzException(@TempDir Path tempDir) throws IOException {
        Path reportPath = tempDir.resolve("blank.ndjson");
        Files.writeString(reportPath, "\n  \n\n");

        assertThatThrownBy(() -> parser.parse(reportPath))
            .isInstanceOf(ApiFuzzException.class)
            .hasMessageContaining("empty")
            .hasMessageContaining("blank.ndjson");
    }
}
