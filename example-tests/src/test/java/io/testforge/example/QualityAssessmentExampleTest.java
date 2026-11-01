package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testforge.quality.QualityDimension;
import io.testforge.quality.QualityException;
import io.testforge.quality.QualityReport;
import io.testforge.quality.QualityRunner;
import io.testforge.quality.QualityStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Judging a finished run. In CI this test is a step of its own after the test
 * jobs, pointed at their {@code build/test-results} and run index; here the
 * "finished run" is written below, as Gradle and module-reporting would have
 * left it: one test failed, one passed only on its retry, and a test left a
 * row behind.
 */
@SpringBootTest
class QualityAssessmentExampleTest {

    static final Path RUN = Path.of("build/quality-example", UUID.randomUUID().toString());

    @DynamicPropertySource
    static void finishedRun(DynamicPropertyRegistry registry) throws IOException {
        write("results/test/TEST-io.acme.OrdersTest.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="io.acme.OrdersTest" tests="4">
                  <testcase classname="io.acme.OrdersTest" name="createsAnOrder()"/>
                  <testcase classname="io.acme.OrdersTest" name="paysAnOrder()">
                    <failure message="Connection reset"/>
                  </testcase>
                  <testcase classname="io.acme.OrdersTest" name="paysAnOrder()"/>
                  <testcase classname="io.acme.OrdersTest" name="refundsAnOrder()">
                    <failure message="expected status 200, was 500"/>
                  </testcase>
                </testsuite>
                """);
        write("artifacts/20261101T120000Z-ci-42/manifest.json", """
                {"runId": "20261101T120000Z-ci-42", "artifactCount": 1, "reportingProblems": [],
                 "artifacts": [{"source": "module-data", "category": "leftovers", "name": "leftovers.json",
                                "metadata": {"healthy": "false", "leftovers": "1"}}]}
                """);
        registry.add("forge.quality.enabled", () -> "true");
        registry.add("forge.quality.junit-results", () -> RUN.resolve("results").toString());
        registry.add("forge.quality.artifacts-root", () -> RUN.resolve("artifacts").toString());
        registry.add("forge.quality.output-dir", () -> RUN.resolve("quality").toString());
        registry.add("forge.quality.baseline-file", () -> RUN.resolve("baseline/quality-baseline.json").toString());
        // one flaky test in three is above any sane limit; 50% keeps this example at WARN
        registry.add("forge.quality.max-flaky-rate", () -> "0.5");
    }

    @Autowired
    QualityRunner quality;

    @Test
    void theReportScoresEachDimensionFromCountedFacts() throws IOException {
        QualityReport report = quality.run();

        assertThat(report.runId()).isEqualTo("20261101T120000Z-ci-42");
        assertThat(report.dimension(QualityDimension.FUNCTIONAL)).get().satisfies(functional -> {
            assertThat(functional.status()).isEqualTo(QualityStatus.FAIL);
            // 2 passed (the retried one counts as passed) of 3
            assertThat(functional.score()).isEqualTo(67);
            assertThat(functional.facts()).contains(
                    "failed: io.acme.OrdersTest.refundsAnOrder() — expected status 200, was 500");
        });
        assertThat(report.dimension(QualityDimension.STABILITY)).get().satisfies(stability -> {
            assertThat(stability.status()).isEqualTo(QualityStatus.WARN);
            assertThat(stability.facts()).contains("flaky: io.acme.OrdersTest.paysAnOrder() — Connection reset");
        });
        assertThat(report.dimension(QualityDimension.HYGIENE)).get()
                .extracting(QualityDimension::status).isEqualTo(QualityStatus.WARN);
        assertThat(Files.readString(Path.of(report.reportMarkdown())))
                .contains("- overall: **FAIL**")
                .contains("| stability | WARN | 67 | — | 1 | `100 × (1 − flaky / executed)` |");
    }

    @Test
    void theGateNamesWhatFailed() {
        assertThatThrownBy(() -> quality.assertAcceptable())
                .isInstanceOf(QualityException.class)
                .hasMessageStartingWith("Quality gate failed on functional (3 tests: 2 passed, 1 failed, 0 skipped, "
                        + "new: io.acme.OrdersTest.refundsAnOrder())");
    }

    @Test
    void aKnownFailureIsAcceptedOnceTheTeamHasDecided() throws IOException {
        // a reviewed change promotes this run: its failing test is now known, not new
        quality.writeBaseline();
        try {
            QualityReport report = quality.assertAcceptable();

            assertThat(report.baselineRunId()).isEqualTo("20261101T120000Z-ci-42");
            assertThat(report.dimension(QualityDimension.FUNCTIONAL).orElseThrow().delta().knownIssues())
                    .containsExactly("io.acme.OrdersTest.refundsAnOrder()");
        } finally {
            // the other examples judge this run without a baseline
            Files.deleteIfExists(RUN.resolve("baseline/quality-baseline.json"));
        }
    }

    private static void write(String relative, String content) throws IOException {
        Path file = RUN.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
