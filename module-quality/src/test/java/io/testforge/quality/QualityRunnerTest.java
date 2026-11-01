package io.testforge.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.artifact.ArtifactSink;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QualityRunnerTest {

    @TempDir
    Path dir;

    private QualityRunner runner(boolean enabled, double maxFlakyRate) {
        return runner(enabled, maxFlakyRate, List.of());
    }

    private QualityRunner runner(boolean enabled, double maxFlakyRate, List<String> accepted) {
        return new QualityRunner(new QualityProperties(enabled, List.of(dir.resolve("results").toString()),
                dir.resolve("artifacts").toString(), null, dir.resolve("quality").toString(), maxFlakyRate, null,
                dir.resolve("baseline/quality-baseline.json").toString(), accepted),
                new ObjectMapper(), ArtifactSink.NO_OP);
    }

    private void junit(String file, String xml) throws IOException {
        Path path = dir.resolve("results/test").resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, xml);
    }

    private void manifest(String runId, String json) throws IOException {
        Path path = dir.resolve("artifacts").resolve(runId).resolve("manifest.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, json);
    }

    private static String suite(String cases) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><testsuite name=\"s\">" + cases + "</testsuite>";
    }

    @Test
    void outcomesAreCountedOncePerTestAndARetriedPassIsFlakyNotFailed() throws IOException {
        junit("TEST-Orders.xml", suite(
                "<testcase classname=\"Orders\" name=\"creates\"/>"
                + "<testcase classname=\"Orders\" name=\"pays\"><failure message=\"timeout&#10;at x\"/></testcase>"
                // Gradle test-retry: the same test again, now passing
                + "<testcase classname=\"Orders\" name=\"pays\"/>"
                + "<testcase classname=\"Orders\" name=\"refunds\"><failure message=\"expected 200, got 500\"/></testcase>"
                + "<testcase classname=\"Orders\" name=\"later\"><skipped/></testcase>"));
        junit("TEST-Billing.xml", suite(
                // Surefire rerunFailingTestsCount
                "<testcase classname=\"Billing\" name=\"bills\"><flakyFailure message=\"connection reset\"/></testcase>"));

        QualityReport report = runner(false, 0.5).run();

        QualityDimension functional = report.dimension(QualityDimension.FUNCTIONAL).orElseThrow();
        assertThat(functional.status()).isEqualTo(QualityStatus.FAIL);
        assertThat(functional.score()).isEqualTo(75);
        assertThat(functional.facts()).containsExactly("5 tests: 3 passed, 1 failed, 1 skipped",
                "failed: Orders.refunds — expected 200, got 500");
        QualityDimension stability = report.dimension(QualityDimension.STABILITY).orElseThrow();
        assertThat(stability.status()).isEqualTo(QualityStatus.WARN);
        assertThat(stability.score()).isEqualTo(50);
        assertThat(stability.facts()).contains("2 of 4 executed tests flaky (rate 50.0%, limit 50.0%)",
                "flaky: Orders.pays — timeout", "flaky: Billing.bills — connection reset");
        assertThat(report.dimension(QualityDimension.HYGIENE).orElseThrow().status())
                .isEqualTo(QualityStatus.NOT_MEASURED);
        assertThat(report.overall()).isEqualTo(QualityStatus.FAIL);
        assertThat(Files.readString(Path.of(report.reportMarkdown())))
                .contains("| functional | FAIL | 75 | — | 1 | `100 × passed / (passed + failed)` |");
        assertThat(new ObjectMapper().readTree(Path.of(report.reportJson()).toFile()).path("overall").asText())
                .isEqualTo("FAIL");
    }

    @Test
    void hygieneReadsTheNewestRunIndex() throws IOException {
        junit("TEST-A.xml", suite("<testcase classname=\"A\" name=\"ok\"/>"));
        manifest("20261101-0900-old", "{\"runId\":\"20261101-0900-old\",\"artifacts\":[]}");
        manifest("20261101-1000-new", "{\"runId\":\"20261101-1000-new\",\"reportingProblems\":[\"sink full\"],"
                + "\"artifacts\":[{\"source\":\"module-data\",\"category\":\"leftovers\",\"name\":\"l.json\","
                + "\"metadata\":{\"leftovers\":\"3\"}}]}");

        QualityReport report = runner(false, 0.02).run();

        assertThat(report.runId()).isEqualTo("20261101-1000-new");
        QualityDimension hygiene = report.dimension(QualityDimension.HYGIENE).orElseThrow();
        assertThat(hygiene.status()).isEqualTo(QualityStatus.WARN);
        assertThat(hygiene.score()).isEqualTo(75);
        assertThat(hygiene.facts()).contains("3 rows tests left behind (module-data leftovers)",
                "reporting problem: sink full");
        assertThat(report.overall()).isEqualTo(QualityStatus.WARN);
        assertThat(report.meanScore()).isEqualTo(92);
    }

    @Test
    void anEarlierQualityChecksOwnRunIsNeverTheRunJudged() throws IOException {
        junit("TEST-A.xml", suite("<testcase classname=\"A\" name=\"ok\"/>"));
        manifest("20261101-1000-tests", "{\"runId\":\"20261101-1000-tests\",\"artifacts\":[{\"source\":"
                + "\"module-data\",\"category\":\"leftovers\",\"metadata\":{\"leftovers\":\"2\"}}]}");
        // the gate's own JVM, run a minute later: only module-quality's report in it
        manifest("20261101-1001-gate", "{\"runId\":\"20261101-1001-gate\",\"artifacts\":[{\"source\":"
                + "\"module-quality\",\"category\":\"quality\",\"name\":\"quality.md\"}]}");

        assertThat(runner(false, 0.02).run().runId()).isEqualTo("20261101-1000-tests");
    }

    @Test
    void runsOfSeveralJvmsAreJudgedTogether() throws IOException {
        junit("TEST-A.xml", suite("<testcase classname=\"A\" name=\"ok\"/>"));
        manifest("unit", "{\"runId\":\"unit\",\"artifacts\":[{\"source\":\"module-data\","
                + "\"category\":\"leftovers\",\"metadata\":{\"leftovers\":\"1\"}}]}");
        manifest("containers", "{\"runId\":\"containers\",\"reportingProblems\":[\"disk full\"],"
                + "\"artifacts\":[]}");

        QualityReport report = new QualityRunner(new QualityProperties(false, List.of(dir.resolve("results").toString()),
                dir.resolve("artifacts").toString(), "unit, containers", dir.resolve("quality").toString(), 0.02,
                null), new ObjectMapper(), ArtifactSink.NO_OP).run();

        assertThat(report.runId()).isEqualTo("unit,containers");
        assertThat(report.dimension(QualityDimension.HYGIENE).orElseThrow().facts())
                .contains("1 rows tests left behind (module-data leftovers)", "reporting problem: disk full");
    }

    @Test
    void staleSuitesAreDroppedAndARequiredDimensionMustBeMeasured() throws IOException {
        junit("TEST-Old.xml", "<testsuite timestamp=\"2026-10-30T10:00:00Z\">"
                + "<testcase classname=\"Old\" name=\"broken\"><failure message=\"long gone\"/></testcase></testsuite>");
        junit("TEST-New.xml", "<testsuite timestamp=\"2026-11-01T10:00:00\">"
                + "<testcase classname=\"New\" name=\"ok\"/></testsuite>");
        QualityProperties properties = new QualityProperties(true, List.of(dir.resolve("results").toString()),
                dir.resolve("artifacts").toString(), null, dir.resolve("quality").toString(), 0.02, null, null, null,
                null, List.of(QualityDimension.FUNCTIONAL, QualityDimension.CONTRACTS),
                java.time.Instant.parse("2026-10-31T00:00:00Z"));
        QualityRunner gate = new QualityRunner(properties, new ObjectMapper(), ArtifactSink.NO_OP);

        QualityReport report = gate.run();

        assertThat(report.dimension(QualityDimension.FUNCTIONAL).orElseThrow().facts().getFirst())
                .isEqualTo("1 tests: 1 passed, 0 failed, 0 skipped");
        assertThat(report.inputs()).anyMatch(input -> input.endsWith("TEST-Old.xml (dropped: older than "
                + "2026-10-31T00:00:00Z)"));
        assertThat(report.measured()).isEqualTo(2);
        assertThat(Files.readString(Path.of(report.reportMarkdown())))
                .contains("- measured: 2 of 9 dimensions — not measured: contracts, data, robustness");
        assertThatThrownBy(gate::assertAcceptable)
                .hasMessageStartingWith("Required dimensions not measured: contracts (no contract-guard report");
    }

    @Test
    void anXmlFileWithADoctypeIsNotParsed() throws IOException {
        junit("TEST-evil.xml", "<?xml version=\"1.0\"?><!DOCTYPE t [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<testsuite><testcase classname=\"E\" name=\"&x;\"/></testsuite>");
        junit("TEST-A.xml", suite("<testcase classname=\"A\" name=\"ok\"/>"));

        assertThat(runner(false, 0.02).run().dimension(QualityDimension.FUNCTIONAL).orElseThrow().facts())
                .containsExactly("1 tests: 1 passed, 0 failed, 0 skipped");
    }

    @Test
    void theGateFailsClosedAndOnlyOnFailOnDimensions() throws IOException {
        assertThatThrownBy(() -> runner(false, 0.02).assertAcceptable())
                .isInstanceOf(QualityException.class).hasMessageContaining("forge.quality.enabled=true");
        assertThatThrownBy(() -> runner(true, 0.02).assertAcceptable())
                .hasMessageContaining("Nothing to assess");

        junit("TEST-A.xml", suite("<testcase classname=\"A\" name=\"ok\"/>"
                + "<testcase classname=\"A\" name=\"broken\"><error message=\"NPE\"/></testcase>"));

        assertThatThrownBy(() -> runner(true, 0.02).assertAcceptable())
                .hasMessageStartingWith("Quality gate failed on functional (2 tests: 1 passed, 1 failed, 0 skipped, "
                        + "new: A.broken)");
    }

    @Test
    void withABaselineOnlyANewIssueFailsTheGate() throws IOException {
        junit("TEST-A.xml", suite("<testcase classname=\"A\" name=\"ok\"/>"
                + "<testcase classname=\"A\" name=\"known\"><failure message=\"old bug\"/></testcase>"
                + "<testcase classname=\"A\" name=\"fixed\"><failure message=\"fixed soon\"/></testcase>"));
        runner(true, 0.02).run();
        assertThat(dir.resolve("baseline/quality-baseline.json")).as("run() never writes a baseline").doesNotExist();
        runner(true, 0.02).writeBaseline();

        junit("TEST-A.xml", suite("<testcase classname=\"A\" name=\"ok\"/>"
                + "<testcase classname=\"A\" name=\"known\"><failure message=\"old bug\"/></testcase>"
                + "<testcase classname=\"A\" name=\"fixed\"/>"));
        QualityReport known = runner(true, 0.02).assertAcceptable();
        assertThat(known.dimension(QualityDimension.FUNCTIONAL).orElseThrow().delta()).satisfies(delta -> {
            assertThat(delta.baselineScore()).isEqualTo(33);
            assertThat(delta.knownIssues()).containsExactly("A.known");
            assertThat(delta.resolvedIssues()).containsExactly("A.fixed");
            assertThat(delta.newIssues()).isEmpty();
        });

        junit("TEST-A.xml", suite("<testcase classname=\"A\" name=\"ok\"><error message=\"NPE\"/></testcase>"
                + "<testcase classname=\"A\" name=\"known\"><failure message=\"old bug\"/></testcase>"));
        assertThatThrownBy(() -> runner(true, 0.02).assertAcceptable())
                .hasMessageContaining("new: A.ok)");
        QualityReport accepted = runner(true, 0.02,
                List.of("functional   A.ok  # ORD-12, fixed in the next release")).assertAcceptable();
        assertThat(accepted.dimension(QualityDimension.FUNCTIONAL).orElseThrow().delta().acceptedIssues())
                .containsExactly("A.ok");
        assertThat(Files.readString(Path.of(accepted.reportMarkdown())))
                .contains("| functional | FAIL | 0 | 33 | 0 |")
                .contains("- accepted (1): `A.ok`")
                .contains("- known since the baseline (1): `A.known`");
    }
}
