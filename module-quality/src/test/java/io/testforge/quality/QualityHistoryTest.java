package io.testforge.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.artifact.ArtifactSink;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QualityHistoryTest {

    @TempDir
    Path dir;

    private final ObjectMapper mapper = new ObjectMapper();

    private QualityRunner runner(String revision) {
        return new QualityRunner(new QualityProperties(false, List.of(dir.resolve("results").toString()),
                dir.resolve("artifacts").toString(), null, dir.resolve("quality").toString(), 0.5, null,
                dir.resolve("baseline.json").toString(), null, null, null, null, null,
                new QualityProperties.HistoryProperties(dir.resolve("history").toString(), 10, 4, 3, revision)),
                mapper, ArtifactSink.NO_OP);
    }

    private void junit(String cases) throws IOException {
        Path path = dir.resolve("results/TEST-s.xml");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "<testsuite name=\"s\">" + cases + "</testsuite>");
    }

    private void entry(String runId, String at, String revision, String overall, Map<String, Integer> scores,
                       Map<String, String> outcomes, Map<String, Long> durations) throws IOException {
        Path file = dir.resolve("history/run-" + runId + ".json");
        Files.createDirectories(file.getParent());
        mapper.writeValue(file.toFile(), new QualityHistory.Entry(1, runId, at, revision, overall, scores, outcomes,
                durations));
    }

    @Test
    void eachRunIsRecordedAndTheOldestBeyondKeepAreDropped() throws IOException {
        junit("<testcase classname=\"T\" name=\"a\" time=\"0.2\"/>");
        for (int i = 1; i <= 5; i++) {
            entry("old-" + i, "2026-10-0" + i + "T10:00:00Z", "r0", "PASS", Map.of(), Map.of(), Map.of());
        }
        Files.writeString(dir.resolve("history/run-broken.json"), "{not json");
        Files.writeString(dir.resolve("history/notes.txt"), "not an entry");

        QualityReport report = runner("r1").run();

        assertThat(report.inputs()).anyMatch(input -> input.contains("(history: 5 earlier runs, revision r1, "
                + "skipped run-broken.json (unreadable))"));
        try (var files = Files.list(dir.resolve("history"))) {
            assertThat(files.map(f -> f.getFileName().toString()).filter(f -> !f.startsWith("run-old-")).sorted())
                    .as("this run's entry is added, the broken and foreign files are left alone")
                    .hasSize(3).contains("notes.txt", "run-broken.json");
        }
        try (var files = Files.list(dir.resolve("history"))) {
            assertThat(files.map(f -> f.getFileName().toString()).filter(f -> f.startsWith("run-old-")).sorted())
                    .as("keep 4: the two oldest are gone").containsExactly("run-old-3.json", "run-old-4.json",
                            "run-old-5.json");
        }
        QualityHistory.Entry recorded = QualityHistory.read(dir.resolve("history"), null, 10, mapper).entries()
                .getLast();
        assertThat(recorded.revision()).isEqualTo("r1");
        assertThat(recorded.outcomes()).containsEntry("T.a", "PASSED");
        assertThat(recorded.durations()).containsEntry("T.a", 200L);
        assertThat(recorded.scores()).containsEntry(QualityDimension.FUNCTIONAL, 100);
    }

    @Test
    void entriesAreOrderedByTheirInstantAndTheCurrentRunIsNotReadBack() throws IOException {
        entry("b", "2026-10-01T10:00:00Z", null, "PASS", Map.of(), Map.of(), Map.of());
        entry("a", "2026-10-01T10:00:00.500Z", null, "PASS", Map.of(), Map.of(), Map.of());
        entry("c", "2026-10-02T10:00:00Z", null, "PASS", Map.of(), Map.of(), Map.of());
        new ObjectMapper().writeValue(dir.resolve("history/run-future.json").toFile(),
                Map.of("formatVersion", 2, "recordedAt", "2026-10-03T10:00:00Z"));

        QualityHistory history = QualityHistory.read(dir.resolve("history"), "c", 10, mapper);

        assertThat(history.entries()).extracting(QualityHistory.Entry::runId).containsExactly("b", "a");
        assertThat(history.skipped()).containsExactly("run-future.json (formatVersion 2)");
    }

    @Test
    void aTestThatFailedAndPassedOnTheSameRevisionIsFlakyAcrossRuns() throws IOException {
        entry("1", "2026-10-01T10:00:00Z", "r1", "FAIL", Map.of(),
                Map.of("T.a", "FAILED", "T.b", "PASSED", "T.c", "FAILED"), Map.of());
        entry("2", "2026-10-02T10:00:00Z", "r0", "FAIL", Map.of(), Map.of("T.b", "FAILED"), Map.of());
        junit("<testcase classname=\"T\" name=\"a\"/><testcase classname=\"T\" name=\"b\"/>"
                + "<testcase classname=\"T\" name=\"c\"><failure message=\"x\"/></testcase>");

        QualityDimension stability = runner("r1").run().dimension(QualityDimension.STABILITY).orElseThrow();

        assertThat(stability.issues()).as("b failed only on other code; c failed every time")
                .containsExactly("T.a");
        assertThat(stability.facts()).contains("1 of 3 executed tests flaky (rate 33.3%, limit 50.0%)",
                "1 tests changed outcome across 2 runs of revision r1", "flaky across runs: T.a");
        assertThat(stability.status()).isEqualTo(QualityStatus.WARN);
    }

    @Test
    void withoutARevisionOutcomesAreNotComparedAcrossRuns() throws IOException {
        entry("1", "2026-10-01T10:00:00Z", null, "FAIL", Map.of(), Map.of("T.a", "FAILED"), Map.of());
        junit("<testcase classname=\"T\" name=\"a\"/>");

        QualityDimension stability = runner(null).run().dimension(QualityDimension.STABILITY).orElseThrow();

        assertThat(stability.issues()).isEmpty();
        assertThat(stability.facts()).anyMatch(fact -> fact.startsWith("history: no revision for this run"));
    }

    @Test
    void aTestSlowerThanItsOwnMedianIsAnEfficiencyIssueAtTheRunsPace() throws IOException {
        for (int i = 1; i <= 3; i++) {
            entry(String.valueOf(i), "2026-10-0" + i + "T10:00:00Z", "r", "PASS", Map.of(), Map.of(),
                    Map.of("T.a", 1000L + (i == 2 ? 100 : 0), "T.b", 2000L, "T.c", 500L, "T.d", 400L));
        }
        // the machine is 1.5× slower for everyone; a is 6× slower on top of that
        junit("<testcase classname=\"T\" name=\"a\" time=\"9.0\"/><testcase classname=\"T\" name=\"b\" time=\"3.0\"/>"
                + "<testcase classname=\"T\" name=\"c\" time=\"0.75\"/><testcase classname=\"T\" name=\"d\" time=\"0.6\"/>"
                + "<testcase classname=\"T\" name=\"e\" time=\"30\"/>");

        QualityDimension efficiency = runner("r").run().dimension(QualityDimension.EFFICIENCY).orElseThrow();

        assertThat(efficiency.status()).isEqualTo(QualityStatus.WARN);
        assertThat(efficiency.issues()).as("e has no history; b, c, d are only as slow as the machine")
                .containsExactly("T.a");
        assertThat(efficiency.score()).isEqualTo(75);
        assertThat(efficiency.facts().getFirst()).isEqualTo("4 tests compared with the median of their last runs "
                + "(at least 3), run pace ×1.50 divided out, 1 above median + 3.0 × MAD and 1000 ms");
        assertThat(efficiency.facts()).contains("slower: T.a median 1000 ms (MAD 50) → 9000 ms, 6000 ms at the "
                + "run's pace");
    }

    @Test
    void anOperationWhoseP95RoseAboveItsMedianIsAnEfficiencyIssue() throws IOException {
        for (int i = 1; i <= 3; i++) {
            Path file = dir.resolve("history/run-" + i + ".json");
            Files.createDirectories(file.getParent());
            mapper.writeValue(file.toFile(), new QualityHistory.Entry(1, String.valueOf(i), "2026-10-0" + i
                    + "T10:00:00Z", "r", "PASS", Map.of(), Map.of(), Map.of(),
                    Map.of("GET /orders/{n}", 80L + i, "GET /health", 5L)));
        }
        junit("<testcase classname=\"T\" name=\"a\"/>");
        Path summary = dir.resolve("artifacts/r9/module-http/http-summary.json");
        Files.createDirectories(summary.getParent());
        Files.writeString(summary, """
                {"formatVersion": 1, "operations": [
                  {"operation": "GET /orders/{n}", "requests": 40, "status": {"2xx": 40}, "transportErrors": 0,
                   "p50Millis": 300, "p95Millis": 450, "maxMillis": 600},
                  {"operation": "GET /health", "requests": 3, "status": {"2xx": 3}, "transportErrors": 0,
                   "p50Millis": 40, "p95Millis": 60, "maxMillis": 60}]}""");
        Files.writeString(dir.resolve("artifacts/r9/manifest.json"), """
                {"runId": "r9", "artifacts": [{"source": "module-http", "category": "http-summary",
                  "name": "http-summary.json", "path": "module-http/http-summary.json"}]}""");

        QualityReport report = runner("r").run();

        QualityDimension efficiency = report.dimension(QualityDimension.EFFICIENCY).orElseThrow();
        assertThat(efficiency.issues()).as("/health rose 12× but by less than min-latency-millis")
                .containsExactly("operation GET /orders/{n}");
        assertThat(efficiency.facts()).containsExactly(
                "2 API operations' p95 compared with their median, 1 above median + 3.0 × MAD and 100 ms",
                "slower operation: GET /orders/{n} p95 median 82 ms (MAD 4) → 450 ms");
        assertThat(QualityHistory.read(dir.resolve("history"), null, 10, mapper).entries().getLast().latencies())
                .containsEntry("GET /orders/{n}", 450L);
    }

    @Test
    void tooFewRunsFallBackToTheBaseline() throws IOException {
        entry("1", "2026-10-01T10:00:00Z", "r", "PASS", Map.of(), Map.of(), Map.of("T.a", 1000L));
        junit("<testcase classname=\"T\" name=\"a\" time=\"9.0\"/>");

        QualityDimension efficiency = runner("r").run().dimension(QualityDimension.EFFICIENCY).orElseThrow();

        assertThat(efficiency.status()).isEqualTo(QualityStatus.NOT_MEASURED);
        assertThat(efficiency.facts().getFirst()).startsWith("needs a baseline");
    }

    @Test
    void aScoreFallingThreeRunsInARowTurnsAPassIntoAWarn() {
        QualityHistory history = new QualityHistory(List.of(
                new QualityHistory.Entry(1, "1", "2026-10-01T10:00:00Z", null, "PASS", Map.of("x", 100), null, null),
                new QualityHistory.Entry(1, "2", "2026-10-02T10:00:00Z", null, "PASS", Map.of("x", 95), null, null),
                new QualityHistory.Entry(1, "3", "2026-10-03T10:00:00Z", null, "PASS", Map.of("x", 90), null, null)),
                List.of());
        QualityDimension falling = HistoryDimensions.trend(
                new QualityDimension("x", QualityStatus.PASS, 85, "f", List.of("fact")), history);
        QualityDimension steady = HistoryDimensions.trend(
                new QualityDimension("x", QualityStatus.PASS, 90, "f", List.of("fact")), history);

        assertThat(falling.status()).isEqualTo(QualityStatus.WARN);
        assertThat(falling.facts()).containsExactly("fact", "trend over 4 runs: 100 → 95 → 90 → 85",
                "score fell in each of the last 3 runs");
        assertThat(falling.issues()).as("a trend never adds an issue the gate could fail on").isEmpty();
        assertThat(steady.status()).isEqualTo(QualityStatus.PASS);
    }

    @Test
    void steadyRunsSuggestPromotingTheBaselineButNeverWriteIt() throws IOException {
        for (int i = 1; i <= 4; i++) {
            entry(String.valueOf(i), "2026-10-0" + i + "T10:00:00Z", "r", i == 1 ? "FAIL" : "PASS", Map.of(),
                    Map.of(), Map.of());
        }
        junit("<testcase classname=\"T\" name=\"a\"/>");

        QualityReport unsteady = runner("r").run();
        entry("5", "2026-10-05T10:00:00Z", "r", "WARN", Map.of(), Map.of(), Map.of());
        QualityReport steady = runner("r").run();

        assertThat(unsteady.notes()).as("one of the last five FAILed").isEmpty();
        assertThat(steady.notes()).containsExactly("the last 5 runs had no FAIL and there is no baseline: "
                + "writeBaseline() in a reviewed change would start comparing against this run");
        assertThat(Files.readString(Path.of(steady.reportMarkdown()))).contains("- note: the last 5 runs had no FAIL");
        assertThat(dir.resolve("baseline.json")).doesNotExist();
    }
}
