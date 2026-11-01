package io.testforge.quality;

import io.testforge.quality.JunitResults.Outcome;
import io.testforge.quality.JunitResults.TestCase;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The dimensions, each a small formula over counted facts. Every formula is
 * printed next to its score, so a reader can recompute it by hand.
 */
final class QualityAssessor {

    static final int LISTED = 10;

    private QualityAssessor() {
    }

    static QualityReport assess(String runId, List<TestCase> tests, RunIndex index, QualityBaseline baseline,
                                List<String> inputs, QualityProperties properties,
                                com.fasterxml.jackson.databind.ObjectMapper mapper) {
        return assess(runId, tests, index, baseline, inputs, properties, mapper, QualityHistory.empty(), null);
    }

    static QualityReport assess(String runId, List<TestCase> tests, RunIndex index, QualityBaseline baseline,
                                List<String> inputs, QualityProperties properties,
                                com.fasterxml.jackson.databind.ObjectMapper mapper, QualityHistory history,
                                String revision) {
        QualityDimension byHistory = ModuleDimensions.guarded(QualityDimension.EFFICIENCY,
                () -> HistoryDimensions.efficiency(tests, history, properties.efficiency(),
                        properties.history().minRuns(), ModuleDimensions.latencies(index, mapper)));
        List<QualityDimension> dimensions = java.util.stream.Stream.of(
                functional(tests, properties),
                stability(tests, properties, history, revision),
                ModuleDimensions.guarded(QualityDimension.CONTRACTS, () -> ModuleDimensions.contracts(index, mapper)),
                ModuleDimensions.guarded(QualityDimension.DATA, () -> ModuleDimensions.data(index, mapper)),
                ModuleDimensions.guarded(QualityDimension.ROBUSTNESS, () -> ModuleDimensions.robustness(index, mapper)),
                ModuleDimensions.guarded(QualityDimension.AUTHORIZATION,
                        () -> ModuleDimensions.authorization(index, mapper)),
                ModuleDimensions.guarded(QualityDimension.SECURITY, () -> ModuleDimensions.security(index, mapper)),
                ModuleDimensions.guarded(QualityDimension.RELIABILITY, () -> ModuleDimensions.reliability(index, mapper)),
                ModuleDimensions.guarded(QualityDimension.DB_QUERIES, () -> ModuleDimensions.dbQueries(index, mapper)),
                ModuleDimensions.guarded(QualityDimension.API_COVERAGE,
                        () -> ModuleDimensions.apiCoverage(index, mapper, properties.minApiCoverage())),
                ModuleDimensions.guarded(QualityDimension.FLOW_COVERAGE,
                        () -> ModuleDimensions.flowCoverage(index, mapper)),
                byHistory != null ? byHistory : ModuleDimensions.efficiency(tests, baseline, properties.efficiency()),
                ModuleDimensions.guarded(QualityDimension.HYGIENE, () -> hygiene(tests, index, mapper)))
                .map(dimension -> HistoryDimensions.trend(dimension, history))
                .toList();
        QualityStatus overall = QualityStatus.NOT_MEASURED;
        int sum = 0;
        int measured = 0;
        for (QualityDimension dimension : dimensions) {
            if (dimension.status() == QualityStatus.NOT_MEASURED) {
                continue;
            }
            measured++;
            sum += dimension.score();
            if (overall == QualityStatus.NOT_MEASURED || dimension.status().worseThan(overall)) {
                overall = dimension.status();
            }
        }
        return new QualityReport(runId, null, overall, measured == 0 ? null : Math.round((float) sum / measured),
                dimensions, inputs, null, null);
    }

    static QualityDimension functional(List<TestCase> tests, QualityProperties properties) {
        long passed = count(tests, Outcome.PASSED);
        long failed = count(tests, Outcome.FAILED);
        long skipped = count(tests, Outcome.SKIPPED);
        if (passed + failed == 0) {
            return QualityDimension.notMeasured(QualityDimension.FUNCTIONAL,
                    "no executed tests in " + properties.junitResults());
        }
        List<String> facts = new ArrayList<>();
        facts.add((passed + failed + skipped) + " tests: " + passed + " passed, " + failed + " failed, "
                + skipped + " skipped");
        tests.stream().filter(t -> t.outcome() == Outcome.FAILED).limit(LISTED)
                .forEach(t -> facts.add("failed: " + t.id() + (t.message() == null ? "" : " — " + t.message())));
        if (failed > LISTED) {
            facts.add("… and " + (failed - LISTED) + " more failed");
        }
        return new QualityDimension(QualityDimension.FUNCTIONAL, failed > 0 ? QualityStatus.FAIL : QualityStatus.PASS,
                Math.round(100f * passed / (passed + failed)), "100 × passed / (passed + failed)", facts)
                .withIssues(tests.stream().filter(t -> t.outcome() == Outcome.FAILED).map(TestCase::id).toList());
    }

    static QualityDimension stability(List<TestCase> tests, QualityProperties properties) {
        return stability(tests, properties, QualityHistory.empty(), null);
    }

    /**
     * Flaky: failed and passed within this run (retries), or — with a history — across the runs of
     * this run's revision, where the code did not change.
     */
    static QualityDimension stability(List<TestCase> tests, QualityProperties properties, QualityHistory history,
                                      String revision) {
        long executed = tests.stream().filter(t -> t.outcome() != Outcome.SKIPPED).count();
        if (executed == 0) {
            return QualityDimension.notMeasured(QualityDimension.STABILITY,
                    "no executed tests in " + properties.junitResults());
        }
        List<TestCase> flaky = tests.stream().filter(TestCase::flaky).toList();
        List<String> byHistory = HistoryDimensions.flakyByHistory(tests, history, revision);
        int count = flaky.size() + byHistory.size();
        double rate = (double) count / executed;
        List<String> facts = new ArrayList<>();
        facts.add(count + " of " + executed + " executed tests flaky (rate " + percent(rate) + ", limit "
                + percent(properties.maxFlakyRate()) + ")");
        String historyFact = HistoryDimensions.stabilityFact(history, revision, byHistory.size());
        if (historyFact != null) {
            facts.add(historyFact);
        }
        flaky.stream().limit(LISTED)
                .forEach(t -> facts.add("flaky: " + t.id() + (t.message() == null ? "" : " — " + t.message())));
        byHistory.stream().limit(LISTED).forEach(id -> facts.add("flaky across runs: " + id));
        QualityStatus status = count == 0 ? QualityStatus.PASS
                : rate > properties.maxFlakyRate() ? QualityStatus.FAIL : QualityStatus.WARN;
        List<String> issues = new ArrayList<>(flaky.stream().map(TestCase::id).toList());
        issues.addAll(byHistory);
        return new QualityDimension(QualityDimension.STABILITY, status, (int) Math.round(100 * (1 - rate)),
                "100 × (1 − flaky / executed)", facts).withIssues(issues);
    }

    static QualityDimension hygiene(List<TestCase> tests, RunIndex index,
                                    com.fasterxml.jackson.databind.ObjectMapper mapper) {
        if (index == null) {
            return QualityDimension.notMeasured(QualityDimension.HYGIENE,
                    "no run index (module-reporting with forge.reporting.artifacts.enabled=true writes one)");
        }
        int leftovers = index.artifacts("module-data", "leftovers").stream()
                .mapToInt(a -> parse(a.metadata().get("leftovers"))).sum();
        int problems = index.reportingProblems().size();
        long skipped = count(tests, Outcome.SKIPPED);
        ModuleDimensions.MockLeftovers mocks = ModuleDimensions.mockLeftovers(index, mapper);
        int stubs = mocks.unusedStubs().size();
        int unmatched = mocks.unmatched().size();
        List<String> facts = new ArrayList<>();
        facts.add(leftovers + " rows tests left behind (module-data leftovers)");
        facts.add(problems + " reporting problems in the run index");
        if (mocks.measured()) {
            facts.add(stubs + " stubs never called and " + unmatched + " requests no stub answered, across "
                    + mocks.scopes() + " mock scopes (module-mock)");
        }
        facts.add(skipped + " skipped tests (not scored: a growing number is worth a look)");
        index.reportingProblems().stream().limit(LISTED).forEach(p -> facts.add("reporting problem: " + p));
        mocks.unusedStubs().stream().limit(LISTED).forEach(stub -> facts.add("unused stub: " + stub));
        mocks.unmatched().stream().limit(LISTED).forEach(request -> facts.add("unmatched request: " + request));
        List<String> issues = new ArrayList<>();
        index.reportingProblems().forEach(p -> issues.add("reporting " + p));
        mocks.unusedStubs().forEach(stub -> issues.add("unused stub " + stub));
        mocks.unmatched().forEach(request -> issues.add("unmatched request " + request));
        boolean clean = leftovers == 0 && problems == 0 && stubs == 0 && unmatched == 0;
        return new QualityDimension(QualityDimension.HYGIENE, clean && skipped == 0 ? QualityStatus.PASS
                : QualityStatus.WARN, Math.max(0, 100 - 5 * leftovers - 10 * problems - 5 * stubs - 5 * unmatched),
                "100 − 5 × leftover rows − 10 × reporting problems − 5 × unused stubs − 5 × unmatched requests",
                facts).withIssues(issues);
    }

    private static long count(List<TestCase> tests, Outcome outcome) {
        return tests.stream().filter(t -> t.outcome() == outcome).count();
    }

    private static int parse(String value) {
        try {
            return value == null ? 1 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String percent(double rate) {
        return String.format(Locale.ROOT, "%.1f%%", rate * 100);
    }
}
