package io.testforge.quality;

import static org.assertj.core.api.Assertions.assertThat;

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
                dir.resolve("artifacts").toString(), null, dir.resolve("quality").toString(), 0.02, null,
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
