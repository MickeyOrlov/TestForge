package io.testforge.quality;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.artifact.ArtifactSink;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Each dimension from a module's report, its score recomputed by hand. */
class ModuleDimensionsTest {

    @TempDir
    Path dir;

    private QualityRunner runner() {
        return new QualityRunner(new QualityProperties(true, List.of(dir.resolve("results").toString()),
                dir.resolve("artifacts").toString(), null, dir.resolve("quality").toString(), 0.02, List.of("functional", "stability", "security"),
                dir.resolve("baseline.json").toString(), List.of(),
                new QualityProperties.EfficiencyProperties(2.0, 500L)), new ObjectMapper(), ArtifactSink.NO_OP);
    }

    private Path file(String relative, String content) throws IOException {
        Path path = dir.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }

    private void junit(String cases) throws IOException {
        file("results/TEST-T.xml", "<testsuite>" + cases + "</testsuite>");
    }

    @Test
    void contractsDataRobustnessAndAuthorizationComeFromTheModulesReports() throws IOException {
        junit("<testcase classname=\"T\" name=\"a\" time=\"0.1\"/>");
        String run = "artifacts/20261101T1000-r";
        file(run + "/module-api-explorer/contract-guard.json", """
                {"checkedResponses": 40, "distinctFindings": 1, "findings": [
                  {"operation": "GET /orders/{id}", "kind": "type", "location": "$.total", "count": 3}]}""");
        Path monitor = file("elsewhere/contract-monitor/report.json", """
                {"healthy": false, "cases": [{"name": "order-created", "failed": true},
                                             {"name": "order-paid", "failed": false}]}""");
        file(run + "/module-db-contract/report.json", """
                {"changes": [
                  {"compatibility": "BREAKING", "change": {"type": "COLUMN_REMOVED", "table": "ORDERS", "object": "AMOUNT"}},
                  {"compatibility": "RISKY", "change": {"type": "COLUMN_NULLABILITY_RELAXED", "table": "ORDERS", "object": "STATUS"}},
                  {"compatibility": "NON_BREAKING", "change": {"type": "COLUMN_ADDED", "table": "ORDERS", "object": "NOTE"}}]}""");
        file(run + "/module-api-fuzz/findings.json", """
                {"outcome": "FINDINGS", "groups": [
                  {"specId": "orders", "key": "not_a_server_error GET /orders", "accepted": false},
                  {"specId": "orders", "key": "status_code_conformance GET /orders", "accepted": true}]}""");
        file(run + "/module-api-explorer/access-matrix.json", """
                {"rows": [{"key": "DELETE /orders/{id}", "cells": [
                  {"role": "admin", "verdict": "OK"}, {"role": "viewer", "verdict": "VIOLATION"}]}]}""");
        file(run + "/manifest.json", """
                {"runId": "20261101T1000-r", "artifacts": [
                  {"source": "module-api-explorer", "category": "contract-guard", "name": "contract-guard.json",
                   "path": "module-api-explorer/contract-guard.json"},
                  {"source": "module-contract-monitor", "category": "contract-monitor", "name": "report.json",
                   "path": "report.json", "metadata": {"location": "%s"}},
                  {"source": "module-db-contract", "category": "db-contract", "name": "report.json",
                   "path": "module-db-contract/report.json"},
                  {"source": "module-api-fuzz", "category": "api-fuzz", "name": "findings.json",
                   "path": "module-api-fuzz/findings.json"},
                  {"source": "module-api-explorer", "category": "access-matrix", "name": "access-matrix.json",
                   "path": "module-api-explorer/access-matrix.json"}]}""".formatted(monitor.toString().replace('\\', '/')));

        QualityReport report = runner().run();

        assertThat(report.dimension(QualityDimension.CONTRACTS).orElseThrow()).satisfies(d -> {
            assertThat(d.status()).isEqualTo(QualityStatus.FAIL);
            assertThat(d.score()).isEqualTo(80);
            assertThat(d.issues()).containsExactly("api GET /orders/{id} type $.total", "event order-created");
        });
        assertThat(report.dimension(QualityDimension.DATA).orElseThrow()).satisfies(d -> {
            assertThat(d.status()).isEqualTo(QualityStatus.FAIL);
            assertThat(d.score()).isEqualTo(30);
            assertThat(d.issues()).containsExactly("BREAKING COLUMN_REMOVED ORDERS.AMOUNT",
                    "RISKY COLUMN_NULLABILITY_RELAXED ORDERS.STATUS");
        });
        assertThat(report.dimension(QualityDimension.ROBUSTNESS).orElseThrow()).satisfies(d -> {
            assertThat(d.score()).isEqualTo(90);
            assertThat(d.issues()).containsExactly("orders:not_a_server_error GET /orders");
            assertThat(d.facts()).contains("1 finding groups not accepted, 1 accepted in the fuzz run");
        });
        assertThat(report.dimension(QualityDimension.AUTHORIZATION).orElseThrow()).satisfies(d -> {
            assertThat(d.score()).isEqualTo(80);
            assertThat(d.issues()).containsExactly("DELETE /orders/{id} as viewer");
        });
        assertThat(report.dimension(QualityDimension.EFFICIENCY).orElseThrow().status())
                .isEqualTo(QualityStatus.NOT_MEASURED);
    }

    @Test
    void withoutTheModulesTheirDimensionsAreNotMeasuredAndSayWhichModuleWould() throws IOException {
        junit("<testcase classname=\"T\" name=\"a\"/>");
        file("artifacts/r/manifest.json", "{\"runId\": \"r\", \"artifacts\": []}");

        QualityReport report = runner().run();

        assertThat(report.dimension(QualityDimension.CONTRACTS).orElseThrow().facts().getFirst())
                .contains("module-api-explorer").contains("module-contract-monitor");
        assertThat(report.dimension(QualityDimension.ROBUSTNESS).orElseThrow().facts().getFirst())
                .contains("module-api-fuzz");
        assertThat(report.overall()).as("NOT MEASURED never drags the overall down or up").isEqualTo(QualityStatus.PASS);
    }

    @Test
    void reliabilityCountsServerErrorsEvenWhenTheTestsPassed() throws IOException {
        junit("<testcase classname=\"T\" name=\"a\"/>");
        file("artifacts/r/module-http/http-summary.json", """
                {"formatVersion": 1, "operations": [
                  {"operation": "GET /orders/{n}", "requests": 8, "status": {"2xx": 7, "5xx": 1}, "transportErrors": 0},
                  {"operation": "POST /orders", "requests": 2, "status": {"2xx": 1}, "transportErrors": 1},
                  {"operation": "GET /health", "requests": 10, "status": {"2xx": 10}, "transportErrors": 0}]}""");
        file("artifacts/r/manifest.json", """
                {"runId": "r", "artifacts": [{"source": "module-http", "category": "http-summary",
                  "name": "http-summary.json", "path": "module-http/http-summary.json"}]}""");

        QualityDimension reliability = runner().run().dimension(QualityDimension.RELIABILITY).orElseThrow();

        assertThat(reliability.status()).isEqualTo(QualityStatus.FAIL);
        assertThat(reliability.score()).isEqualTo(90);
        assertThat(reliability.issues()).containsExactly("GET /orders/{n}", "POST /orders");
        assertThat(reliability.facts().getFirst())
                .isEqualTo("20 HTTP requests, 2 answered 5xx or failed in transport, across 2 operations");
    }

    @Test
    void apiCoverageNamesTheOperationsNoTestCalled() throws IOException {
        junit("<testcase classname=\"T\" name=\"a\"/>");
        file("artifacts/r/module-api-explorer/api-coverage.json", """
                {"formatVersion": 1, "documented": 4,
                 "called": {"demo GET /tasks": 3, "demo GET /tasks/{id}": 1},
                 "uncalled": ["demo POST /tasks", "demo DELETE /tasks/{id}"]}""");
        file("artifacts/r/manifest.json", """
                {"runId": "r", "artifacts": [{"source": "module-api-explorer", "category": "api-coverage",
                  "name": "api-coverage.json", "path": "module-api-explorer/api-coverage.json"}]}""");

        QualityDimension coverage = new QualityRunner(new QualityProperties(true,
                List.of(dir.resolve("results").toString()), dir.resolve("artifacts").toString(), null,
                dir.resolve("quality").toString(), 0.02, null, null, null, null, null, null, 0.8),
                new ObjectMapper(), ArtifactSink.NO_OP).run().dimension(QualityDimension.API_COVERAGE).orElseThrow();

        assertThat(coverage.status()).isEqualTo(QualityStatus.FAIL);
        assertThat(coverage.score()).isEqualTo(50);
        assertThat(coverage.facts().getFirst()).isEqualTo("2 of 4 documented operations called by the suite "
                + "(minimum 80%)");
        assertThat(coverage.issues()).containsExactly("demo POST /tasks", "demo DELETE /tasks/{id}");
    }

    @Test
    void aReportInAnUnknownFormatIsNotMeasuredRatherThanReadAsZero() throws IOException {
        junit("<testcase classname=\"T\" name=\"a\"/>");
        file("artifacts/r/module-api-fuzz/findings.json",
                "{\"formatVersion\": 2, \"outcome\": \"FINDINGS\", \"findingGroups\": []}");
        file("artifacts/r/manifest.json", """
                {"runId": "r", "artifacts": [{"source": "module-api-fuzz", "category": "api-fuzz",
                  "name": "findings.json", "path": "module-api-fuzz/findings.json"}]}""");

        QualityDimension robustness = runner().run().dimension(QualityDimension.ROBUSTNESS).orElseThrow();

        assertThat(robustness.status()).isEqualTo(QualityStatus.NOT_MEASURED);
        assertThat(robustness.facts().getFirst())
                .isEqualTo("module-api-fuzz's findings.json has formatVersion 2; this module reads 1 — update "
                        + "module-quality with it");
    }

    @Test
    void securityScoresTheNewFindingsModuleSecurityReports() throws IOException {
        junit("<testcase classname=\"T\" name=\"a\"/>");
        // the layout of module-security's security-findings.json (format 1)
        file("artifacts/r/module-security/security-findings.json", """
                {"formatVersion": 1, "enabled": true, "measured": true, "complete": true, "healthy": false, "findings": [
                  {"id": "cors-credentials-wildcard GET /orders", "severity": "high", "status": "new"},
                  {"id": "missing-nosniff GET /orders", "severity": "low", "status": "new"},
                  {"id": "server-version GET /", "severity": "info", "status": "new"},
                  {"id": "missing-csp GET /health", "severity": "medium", "status": "accepted"},
                  {"id": "stack-trace GET /fail", "severity": "medium", "status": "resolved"}],
                 "coverage": [], "newCounts": {}}""");
        file("artifacts/r/manifest.json", """
                {"runId": "r", "artifacts": [{"source": "module-security", "category": "security",
                  "name": "security-findings.json", "path": "module-security/security-findings.json"}]}""");

        QualityDimension security = runner().run().dimension(QualityDimension.SECURITY).orElseThrow();

        assertThat(security.status()).isEqualTo(QualityStatus.FAIL);
        assertThat(security.score()).isEqualTo(79);
        assertThat(security.issues()).containsExactly("cors-credentials-wildcard GET /orders",
                "missing-nosniff GET /orders");
        assertThat(security.facts().getFirst()).isEqualTo("new findings: 0 critical, 1 high, 0 medium, 1 low, "
                + "1 info; 1 accepted, 1 resolved (module-security)");
    }

    @Test
    void aSecurityRunThatMeasuredNothingIsNotAPass() throws IOException {
        junit("<testcase classname=\"T\" name=\"a\"/>");
        file("artifacts/r/module-security/security-findings.json",
                "{\"measured\": false, \"findings\": [], \"coverage\": []}");
        file("artifacts/r/manifest.json", """
                {"runId": "r", "artifacts": [{"source": "module-security", "category": "security",
                  "name": "security-findings.json", "path": "module-security/security-findings.json"}]}""");

        assertThat(runner().run().dimension(QualityDimension.SECURITY).orElseThrow().status())
                .isEqualTo(QualityStatus.NOT_MEASURED);
    }

    @Test
    void incompleteSecurityCannotPassTheSelectedGateEvenAfterBaselinePromotion() throws IOException {
        junit("<testcase classname=\"T\" name=\"a\"/>");
        securityReport("{\"measured\":true,\"complete\":false,\"findings\":[],\"coverage\":[]}");
        var security = runner().run().dimension(QualityDimension.SECURITY).orElseThrow();
        assertThat(security.status()).isEqualTo(QualityStatus.NOT_MEASURED);
        assertThat(security.score()).isNull();
        runner().writeBaseline();
        assertThatThrownBy(() -> runner().assertAcceptable()).isInstanceOf(QualityException.class)
                .hasMessageContaining("incomplete");
    }

    @Test
    void coverageGapsCannotHideObservedHighFindings() throws IOException {
        junit("<testcase classname=\"T\" name=\"a\"/>");
        securityReport("""
                {"measured":true,"complete":true,"findings":[
                 {"id":"observed-high","severity":"high","status":"new"}],
                 "coverage":[{"status":"not_measured"}]}""");
        var security = runner().run().dimension(QualityDimension.SECURITY).orElseThrow();
        assertThat(security.status()).isEqualTo(QualityStatus.FAIL);
        assertThat(security.score()).isEqualTo(80);
        assertThat(security.facts()).anyMatch(f -> f.contains("incomplete"));
        runner().writeBaseline();
        assertThatThrownBy(() -> runner().assertAcceptable()).isInstanceOf(QualityException.class);
    }

    private void securityReport(String json) throws IOException {
        file("artifacts/r/module-security/security-findings.json", json);
        file("artifacts/r/manifest.json", """
                {"runId":"r","artifacts":[{"source":"module-security","category":"security",
                "name":"security-findings.json","path":"module-security/security-findings.json"}]}""");
    }

    @Test
    void aTestMarkedlySlowerThanInTheBaselineIsAnEfficiencyIssue() throws IOException {
        junit("<testcase classname=\"T\" name=\"fast\" time=\"0.2\"/>"
                + "<testcase classname=\"T\" name=\"slow\" time=\"0.4\"/>"
                + "<testcase classname=\"T\" name=\"noise\" time=\"0.010\"/>");
        runner().writeBaseline();

        junit("<testcase classname=\"T\" name=\"fast\" time=\"0.3\"/>"     // 1.5×: not slower
                + "<testcase classname=\"T\" name=\"slow\" time=\"1.2\"/>"  // 3× and 800 ms: slower
                + "<testcase classname=\"T\" name=\"noise\" time=\"0.090\"/>"); // 9× but 80 ms: noise
        QualityDimension efficiency = runner().run().dimension(QualityDimension.EFFICIENCY).orElseThrow();

        assertThat(efficiency.status()).isEqualTo(QualityStatus.WARN);
        assertThat(efficiency.score()).isEqualTo(67);
        assertThat(efficiency.issues()).containsExactly("T.slow");
        assertThat(efficiency.facts()).contains("slower: T.slow 400 ms → 1200 ms");
    }
}
