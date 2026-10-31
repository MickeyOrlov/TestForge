package io.testforge.api.fuzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testforge.api.discovery.ApiDiscoveryProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

class FuzzFindingsReportTest {

    @TempDir
    Path tempDir;

    /** Three cases break one check on one operation; one breaks another. */
    private static final String NDJSON = """
            {"Initialize": {"schemathesis_version": "4.24.3", "seed": 4242}}
            {"ScenarioFinished": {"status": "failure", "phase": "Fuzzing", "recorder": {"label": "GET /items/{id}", "cases": {"a": {"value": {"method": "GET", "path": "/items/1"}}, "b": {"value": {"method": "GET", "path": "/items/2"}}}, "checks": {"a": [{"name": "not_a_server_error", "status": "failure", "failure_info": {"failure": {"message": "Server error\\n\\nTraceback follows"}}}], "b": [{"name": "not_a_server_error", "status": "failure", "failure_info": {"failure": {"message": "Server error"}}}]}}}}
            {"ScenarioFinished": {"status": "failure", "phase": "Coverage", "recorder": {"label": "GET /items/{id}", "cases": {"c": {"value": {"method": "TRACE", "path": "/items/3"}}}, "checks": {"c": [{"name": "not_a_server_error", "status": "failure", "failure_info": {"failure": {"message": "Server error"}}}, {"name": "unsupported_method", "status": "failure", "failure_info": {"failure": {"message": "Unsupported method TRACE"}}}]}}}}
            {"ScenarioFinished": {"status": "success", "phase": "Fuzzing", "recorder": {"label": "GET /health", "cases": {}, "checks": {}}}}
            """;

    @Test
    void findingsAreGroupedByCheckAndOperation() {
        List<ApiFuzzFindingGroup> groups = ApiFuzzFindingGroup.of(List.of(
                new ApiFuzzFinding("GET /a", "Fuzzing", "GET", "/a", "x", "first", "demo"),
                new ApiFuzzFinding("GET /a", "Coverage", "TRACE", "/a", "x", "second", "demo"),
                new ApiFuzzFinding("GET /a", "Fuzzing", "GET", "/a", "x", "other spec", "other"),
                new ApiFuzzFinding("GET /b", "Fuzzing", "GET", "/b", "y", null, "demo")));

        assertThat(groups).extracting(ApiFuzzFindingGroup::toString)
                // most frequent first, then by spec, then by key
                .containsExactly("x GET /a (x2)", "y GET /b (x1)", "x GET /a (x1)");
        assertThat(groups.getFirst()).satisfies(group -> {
            assertThat(group.specId()).isEqualTo("demo");
            assertThat(group.phases()).containsExactlyInAnyOrder("Coverage", "Fuzzing");
            assertThat(group.methods()).containsExactlyInAnyOrder("GET", "TRACE");
            assertThat(group.firstMessage()).isEqualTo("first");
        });
    }

    @Test
    void theReportListsEachCheckOnceWithTheCommandThatReproducesIt() throws Exception {
        ApiFuzzRunner runner = runner("https://user:secret@api.staging.example.test", false);

        ApiFuzzReport report = runner.run();

        assertThat(report.findings()).hasSize(4).allSatisfy(finding -> assertThat(finding.specId()).isEqualTo("demo"));
        assertThat(report.findingGroups()).extracting(ApiFuzzFindingGroup::toString)
                .containsExactly("not_a_server_error GET /items/{id} (x3)", "unsupported_method GET /items/{id} (x1)");

        Path markdown = report.artifacts().get("report.md");
        assertThat(markdown).exists();
        String text = Files.readString(markdown);
        assertThat(text)
                .contains("- findings: 4 failing cases in 2 checks")
                .contains("## demo")
                .contains("- scenarios: 3 (2 failed)")
                .contains("#### `not_a_server_error` on `GET /items/{id}` (3 cases)")
                .contains("- phases: Coverage, Fuzzing")
                .contains("- methods sent: GET, TRACE")
                .contains("- first message: Server error\n")
                .contains("--seed 4242")
                .contains("--include-name 'GET /items/{id}'")
                .contains("-u https://api.staging.example.test")
                .doesNotContain("secret")
                .doesNotContain("Traceback")
                .doesNotContain("--report-dir");
    }

    @Test
    void theFailureMessageNamesChecksNotEveryCase() throws Exception {
        ApiFuzzRunner runner = runner("http://localhost:8080", true);

        assertThatThrownBy(runner::assertHealthy)
                .isInstanceOf(ApiFuzzException.class)
                .hasMessageContaining("4 failing cases in 2 new checks")
                .hasMessageContaining("not_a_server_error GET /items/{id} (x3)")
                .hasMessageContaining("report.md");
    }

    @Test
    void anArgumentWithAQuoteIsQuotedForTheShell() {
        assertThat(FuzzMarkdownReport.reproduce(List.of("st", "run", "it's.yaml"), "GET /a"))
                .isEqualTo("st run 'it'\"'\"'s.yaml' --include-name 'GET /a'");
    }

    @Test
    void acceptedFindingsAreMarkedAndTheGateFailsOnlyOnNewOnes() throws Exception {
        ApiFuzzRunner runner = runner("http://localhost:8080", true, List.of(
                "not_a_server_error GET /items/{id}   # TICKET-17, owned by the items team",
                "demo:not_a_server_error GET /gone",
                "  "));

        assertThatThrownBy(runner::assertHealthy)
                .isInstanceOf(ApiFuzzException.class)
                .hasMessageContaining("1 failing case in 1 new check: [unsupported_method GET /items/{id} (x1)]")
                .hasMessageContaining("(1 accepted not counted)");

        ApiFuzzReport report = runner.run();
        assertThat(report.acceptedFindings())
                .containsExactly("not_a_server_error GET /items/{id}", "demo:not_a_server_error GET /gone");
        assertThat(report.newFindingGroups()).extracting(ApiFuzzFindingGroup::key)
                .containsExactly("unsupported_method GET /items/{id}");
        assertThat(report.staleAcceptedFindings()).containsExactly("demo:not_a_server_error GET /gone");
        assertThat(Files.readString(report.artifacts().get("report.md")))
                .contains("- findings: 4 failing cases in 2 checks, 1 accepted")
                .contains("#### `not_a_server_error` on `GET /items/{id}` (3 cases) — accepted")
                .contains("## Accepted findings no longer seen")
                .contains("- `demo:not_a_server_error GET /gone`")
                .doesNotContain("TICKET-17");
    }

    @Test
    void withEveryFindingAcceptedTheGatePasses() throws Exception {
        ApiFuzzRunner runner = runner("http://localhost:8080", true, List.of(
                "demo:not_a_server_error GET /items/{id}",
                "unsupported_method GET /items/{id}"));

        ApiFuzzReport report = runner.assertHealthy();

        assertThat(report.outcome()).isEqualTo(ApiFuzzOutcome.FINDINGS);
        assertThat(report.newFindingGroups()).isEmpty();
        assertThat(report.staleAcceptedFindings()).isEmpty();
    }

    @Test
    void aSpecScopedEntryDoesNotAcceptTheSameFindingInAnotherSpec() {
        List<ApiFuzzFindingGroup> groups = ApiFuzzFindingGroup.of(List.of(
                new ApiFuzzFinding("GET /a", "Fuzzing", "GET", "/a", "x", null, "demo"),
                new ApiFuzzFinding("GET /a", "Fuzzing", "GET", "/a", "x", null, "other")),
                List.of("demo:x GET /a"));

        assertThat(groups).extracting(group -> group.specId() + " " + group.accepted())
                .containsExactlyInAnyOrder("demo true", "other false");
    }

    private ApiFuzzRunner runner(String baseUrl, boolean failOnFindings) throws Exception {
        return runner(baseUrl, failOnFindings, List.of());
    }

    private ApiFuzzRunner runner(String baseUrl, boolean failOnFindings, List<String> accepted) throws Exception {
        Path specFile = tempDir.resolve("demo.yaml");
        Files.writeString(specFile, "openapi: 3.0.0\n");
        ApiDiscoveryProperties discovery = new ApiDiscoveryProperties(null, null, null, null, null,
                Map.of("demo", new ApiDiscoveryProperties.Spec("file:" + specFile.toAbsolutePath())));
        ApiFuzzProperties props = new ApiFuzzProperties(
                true, tempDir.toString(), List.of("demo"), baseUrl,
                null, false, null, 4242L, null, null, null, null, "st", null, failOnFindings, accepted);
        ApiFuzzRunnerTest.FakeProcessRunner process = new ApiFuzzRunnerTest.FakeProcessRunner()
                .withNdjson(tempDir.resolve("demo"), NDJSON);
        return new ApiFuzzRunner(
                new FuzzSpecMaterializer(discovery, new DefaultResourceLoader(), tempDir),
                new SchemathesisExecutor(process),
                new NdjsonReportParser(),
                new FuzzEvidenceWriter(),
                discovery,
                props);
    }
}
