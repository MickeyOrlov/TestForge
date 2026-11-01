package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.quality.QualityDimension;
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
 * What one run cannot tell, read from {@code forge.quality.history.dir}: the
 * same commit failed {@code paysAnOrder()} a run ago and passes now — a flaky
 * test, though this run never retried it; {@code exportsOrders()} takes four
 * times its usual while the other tests keep their pace. In CI the directory
 * is restored from a cache before the gate and saved after it; the gate adds
 * this run's entry itself.
 */
@SpringBootTest
class QualityHistoryExampleTest {

    static final Path RUN = Path.of("build/quality-history-example", UUID.randomUUID().toString());

    @DynamicPropertySource
    static void lastRuns(DynamicPropertyRegistry registry) throws IOException {
        write("results/TEST-io.acme.OrdersTest.xml", """
                <testsuite name="io.acme.OrdersTest">
                  <testcase classname="io.acme.OrdersTest" name="paysAnOrder()" time="0.4"/>
                  <testcase classname="io.acme.OrdersTest" name="listsOrders()" time="0.3"/>
                  <testcase classname="io.acme.OrdersTest" name="exportsOrders()" time="8.0"/>
                </testsuite>
                """);
        for (int run = 1; run <= 3; run++) {
            write("history/run-ci-" + run + ".json", """
                    {"formatVersion": 1, "runId": "ci-%d", "recordedAt": "2026-10-2%dT10:00:00Z",
                     "revision": "%s", "overall": "%s", "scores": {"functional": %d},
                     "outcomes": {"io.acme.OrdersTest.paysAnOrder()": "%s",
                                  "io.acme.OrdersTest.listsOrders()": "PASSED",
                                  "io.acme.OrdersTest.exportsOrders()": "PASSED"},
                     "durations": {"io.acme.OrdersTest.paysAnOrder()": 400,
                                   "io.acme.OrdersTest.listsOrders()": 300,
                                   "io.acme.OrdersTest.exportsOrders()": 2000}}
                    """.formatted(run, run, run == 1 ? "1a2b3c" : "9f8e7d", run == 2 ? "FAIL" : "PASS",
                    run == 2 ? 67 : 100, run == 2 ? "FAILED" : "PASSED"));
        }
        registry.add("forge.quality.junit-results", () -> RUN.resolve("results").toString());
        registry.add("forge.quality.artifacts-root", () -> RUN.resolve("artifacts").toString());
        registry.add("forge.quality.output-dir", () -> RUN.resolve("quality").toString());
        registry.add("forge.quality.baseline-file", () -> RUN.resolve("baseline.json").toString());
        registry.add("forge.quality.max-flaky-rate", () -> "0.5");
        registry.add("forge.quality.history.dir", () -> RUN.resolve("history").toString());
        // CI usually sets GITHUB_SHA / CI_COMMIT_SHA / GIT_COMMIT; the example names the commit itself
        registry.add("forge.quality.history.revision", () -> "9f8e7d");
    }

    @Autowired
    QualityRunner quality;

    @Test
    void theHistoryFindsAFlakyTestAndASlowOneThatOneRunCannotSee() {
        QualityReport report = quality.run();

        assertThat(report.dimension(QualityDimension.STABILITY)).get().satisfies(stability -> {
            assertThat(stability.status()).isEqualTo(QualityStatus.WARN);
            assertThat(stability.issues()).containsExactly("io.acme.OrdersTest.paysAnOrder()");
            assertThat(stability.facts()).contains("1 tests changed outcome across 3 runs of revision 9f8e7d");
        });
        assertThat(report.dimension(QualityDimension.EFFICIENCY)).get().satisfies(efficiency -> {
            assertThat(efficiency.status()).isEqualTo(QualityStatus.WARN);
            assertThat(efficiency.issues()).containsExactly("io.acme.OrdersTest.exportsOrders()");
        });
        assertThat(report.dimension(QualityDimension.FUNCTIONAL).orElseThrow().facts())
                .contains("trend over 4 runs: 100 → 67 → 100 → 100");
        // this run's entry joins the three earlier ones, for the next gate
        assertThat(RUN.resolve("history")).isDirectoryContaining(path -> path.getFileName().toString()
                .startsWith("run-") && !path.getFileName().toString().startsWith("run-ci-"));
    }

    private static void write(String relative, String content) throws IOException {
        Path file = RUN.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
