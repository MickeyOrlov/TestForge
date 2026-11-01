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
        return new QualityRunner(new QualityProperties(enabled, List.of(dir.resolve("results").toString()),
                dir.resolve("artifacts").toString(), null, dir.resolve("quality").toString(), maxFlakyRate, null),
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
                .contains("| functional | FAIL | 75 | `100 × passed / (passed + failed)` |");
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
                .hasMessageStartingWith("Quality gate failed on functional (2 tests: 1 passed, 1 failed, 0 skipped)");
    }
}
