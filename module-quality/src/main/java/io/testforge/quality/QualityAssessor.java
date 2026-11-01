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
        List<QualityDimension> dimensions = List.of(
                functional(tests, properties),
                stability(tests, properties),
                ModuleDimensions.contracts(index, mapper),
                ModuleDimensions.data(index, mapper),
                ModuleDimensions.robustness(index, mapper),
                ModuleDimensions.authorization(index, mapper),
                ModuleDimensions.security(index, mapper),
                ModuleDimensions.efficiency(tests, baseline, properties.efficiency()),
                hygiene(tests, index));
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
        long executed = tests.stream().filter(t -> t.outcome() != Outcome.SKIPPED).count();
        if (executed == 0) {
            return QualityDimension.notMeasured(QualityDimension.STABILITY,
                    "no executed tests in " + properties.junitResults());
        }
        List<TestCase> flaky = tests.stream().filter(TestCase::flaky).toList();
        double rate = (double) flaky.size() / executed;
        List<String> facts = new ArrayList<>();
        facts.add(flaky.size() + " of " + executed + " executed tests flaky (rate " + percent(rate) + ", limit "
                + percent(properties.maxFlakyRate()) + ")");
        flaky.stream().limit(LISTED)
                .forEach(t -> facts.add("flaky: " + t.id() + (t.message() == null ? "" : " — " + t.message())));
        QualityStatus status = flaky.isEmpty() ? QualityStatus.PASS
                : rate > properties.maxFlakyRate() ? QualityStatus.FAIL : QualityStatus.WARN;
        return new QualityDimension(QualityDimension.STABILITY, status, (int) Math.round(100 * (1 - rate)),
                "100 × (1 − flaky / executed)", facts).withIssues(flaky.stream().map(TestCase::id).toList());
    }

    static QualityDimension hygiene(List<TestCase> tests, RunIndex index) {
        if (index == null) {
            return QualityDimension.notMeasured(QualityDimension.HYGIENE,
                    "no run index (module-reporting with forge.reporting.artifacts.enabled=true writes one)");
        }
        int leftovers = index.artifacts("module-data", "leftovers").stream()
                .mapToInt(a -> parse(a.metadata().get("leftovers"))).sum();
        int problems = index.reportingProblems().size();
        long skipped = count(tests, Outcome.SKIPPED);
        List<String> facts = new ArrayList<>();
        facts.add(leftovers + " rows tests left behind (module-data leftovers)");
        facts.add(problems + " reporting problems in the run index");
        facts.add(skipped + " skipped tests (not scored: a growing number is worth a look)");
        index.reportingProblems().stream().limit(LISTED).forEach(p -> facts.add("reporting problem: " + p));
        boolean clean = leftovers == 0 && problems == 0;
        return new QualityDimension(QualityDimension.HYGIENE, clean && skipped == 0 ? QualityStatus.PASS
                : QualityStatus.WARN, Math.max(0, 100 - 5 * leftovers - 10 * problems),
                "100 − 5 × leftover rows − 10 × reporting problems", facts)
                .withIssues(index.reportingProblems().stream().map(p -> "reporting " + p).toList());
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
