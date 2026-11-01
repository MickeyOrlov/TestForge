package io.testforge.quality;

import io.testforge.quality.JunitResults.Outcome;
import io.testforge.quality.JunitResults.TestCase;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What only several runs can tell: a test that changes outcome on unchanged
 * code, a test slower than its own usual, a score that keeps falling.
 */
final class HistoryDimensions {

    static final int LISTED = 10;
    /** Consecutive drops of a score that turn its PASS into a WARN. */
    static final int FALLING_RUNS = 3;
    /** A MAD below this share of the median is noise-free on paper only: it is raised to it. */
    static final double MIN_SPREAD = 0.05;

    private HistoryDimensions() {
    }

    /**
     * Executed tests of this run that both failed and passed across the runs of {@code revision} —
     * the same code, so the outcome depends on something other than the code. Tests already flaky
     * by retry in this run are not repeated.
     */
    static List<String> flakyByHistory(List<TestCase> tests, QualityHistory history, String revision) {
        List<QualityHistory.Entry> same = history.sameRevision(revision);
        if (same.isEmpty()) {
            return List.of();
        }
        List<String> flaky = new ArrayList<>();
        for (TestCase test : tests) {
            if (test.outcome() == Outcome.SKIPPED || test.flaky()) {
                continue;
            }
            boolean failed = test.outcome() == Outcome.FAILED;
            boolean passed = test.outcome() == Outcome.PASSED;
            for (QualityHistory.Entry entry : same) {
                String before = entry.outcomes().get(test.id());
                failed |= "FAILED".equals(before) || "FLAKY".equals(before);
                passed |= "PASSED".equals(before) || "FLAKY".equals(before);
            }
            if (failed && passed) {
                flaky.add(test.id());
            }
        }
        return flaky;
    }

    /** The line stability prints about the history, or {@code null} without one. */
    static String stabilityFact(QualityHistory history, String revision, int flaky) {
        if (history.entries().isEmpty()) {
            return null;
        }
        if (revision == null) {
            return "history: no revision for this run (forge.quality.history.revision, or GITHUB_SHA / CI_COMMIT_SHA "
                    + "/ GIT_COMMIT) — outcomes are compared only between runs of the same code";
        }
        int same = history.sameRevision(revision).size();
        return same == 0 ? "history: no earlier run of revision " + revision
                : flaky + " tests changed outcome across " + (same + 1) + " runs of revision " + revision;
    }

    /**
     * Passed tests against the median of their own last runs, after dividing out the run's pace —
     * the median ratio of this run's durations to those medians, so a slower machine is not a
     * regression of every test. Slower means above median + {@code madFactor} × MAD and at least
     * {@code minMillis} above the median. API operations compare their p95 the same way, without
     * the pace — the server's time is not the test machine's — and with {@code minLatencyMillis}.
     * {@code null} when no test and no operation has {@code minRuns} values.
     */
    static QualityDimension efficiency(List<TestCase> tests, QualityHistory history,
                                       QualityProperties.EfficiencyProperties limits, int minRuns,
                                       Map<String, Long> latencies) {
        Map<String, long[]> past = new LinkedHashMap<>();
        for (TestCase test : tests) {
            if (test.outcome() != Outcome.PASSED || test.millis() < 0) {
                continue;
            }
            long[] durations = history.entries().stream().map(e -> e.durations().get(test.id()))
                    .filter(java.util.Objects::nonNull).mapToLong(Long::longValue).toArray();
            if (durations.length >= minRuns) {
                past.put(test.id(), durations);
            }
        }
        Map<String, long[]> pastLatencies = new LinkedHashMap<>();
        latencies.forEach((operation, p95) -> {
            long[] values = history.entries().stream().map(e -> e.latencies().get(operation))
                    .filter(java.util.Objects::nonNull).mapToLong(Long::longValue).toArray();
            if (values.length >= minRuns) {
                pastLatencies.put(operation, values);
            }
        });
        if (past.isEmpty() && pastLatencies.isEmpty()) {
            return null;
        }
        Map<String, Long> current = tests.stream().filter(t -> past.containsKey(t.id()))
                .collect(Collectors.toMap(TestCase::id, TestCase::millis, (a, b) -> b));
        double[] ratios = past.entrySet().stream().filter(e -> median(e.getValue()) > 0)
                .mapToDouble(e -> current.get(e.getKey()) / median(e.getValue())).toArray();
        double pace = ratios.length == 0 ? 1.0 : median(ratios);
        if (pace <= 0) {
            pace = 1.0;
        }
        List<String> issues = new ArrayList<>();
        List<String> facts = new ArrayList<>();
        for (Map.Entry<String, long[]> test : past.entrySet()) {
            double median = median(test.getValue());
            double spread = Math.max(mad(test.getValue(), median), MIN_SPREAD * median);
            double normalized = current.get(test.getKey()) / pace;
            if (normalized > median + limits.madFactor() * spread && normalized - median >= limits.minMillis()) {
                issues.add(test.getKey());
                if (issues.size() <= LISTED) {
                    facts.add("slower: " + test.getKey() + " median " + Math.round(median) + " ms (MAD "
                            + Math.round(spread) + ") → " + current.get(test.getKey()) + " ms, "
                            + Math.round(normalized) + " ms at the run's pace");
                }
            }
        }
        int slowerTests = issues.size();
        for (Map.Entry<String, long[]> operation : pastLatencies.entrySet()) {
            double median = median(operation.getValue());
            double spread = Math.max(mad(operation.getValue(), median), MIN_SPREAD * median);
            long p95 = latencies.get(operation.getKey());
            if (p95 > median + limits.madFactor() * spread && p95 - median >= limits.minLatencyMillis()) {
                issues.add("operation " + operation.getKey());
                if (issues.size() <= LISTED) {
                    facts.add("slower operation: " + operation.getKey() + " p95 median " + Math.round(median)
                            + " ms (MAD " + Math.round(spread) + ") → " + p95 + " ms");
                }
            }
        }
        int compared = past.size() + pastLatencies.size();
        if (!pastLatencies.isEmpty()) {
            facts.addFirst(pastLatencies.size() + " API operations' p95 compared with their median, "
                    + (issues.size() - slowerTests) + " above median + " + limits.madFactor() + " × MAD and "
                    + limits.minLatencyMillis() + " ms");
        }
        if (!past.isEmpty()) {
            facts.addFirst(past.size() + " tests compared with the median of their last runs (at least " + minRuns
                    + "), run pace ×" + String.format(Locale.ROOT, "%.2f", pace) + " divided out, " + slowerTests
                    + " above median + " + limits.madFactor() + " × MAD and " + limits.minMillis() + " ms");
        }
        return new QualityDimension(QualityDimension.EFFICIENCY, issues.isEmpty() ? QualityStatus.PASS
                : QualityStatus.WARN, Math.round(100f * (compared - issues.size()) / compared),
                "100 × (compared − slower) / compared", facts).withIssues(issues);
    }

    /**
     * Each measured dimension's score over the history, as a fact; a PASS whose score fell in each
     * of the last {@value #FALLING_RUNS} runs becomes a WARN — no issue is added, so the gate and the
     * baseline comparison are unchanged.
     */
    static QualityDimension trend(QualityDimension dimension, QualityHistory history) {
        if (dimension.score() == null) {
            return dimension;
        }
        List<Integer> scores = new ArrayList<>();
        history.entries().forEach(e -> {
            Integer score = e.scores().get(dimension.name());
            if (score != null) {
                scores.add(score);
            }
        });
        if (scores.isEmpty()) {
            return dimension;
        }
        scores.add(dimension.score());
        List<String> facts = new ArrayList<>(dimension.facts());
        facts.add("trend over " + scores.size() + " runs: " + scores.stream().map(String::valueOf)
                .collect(Collectors.joining(" → ")));
        QualityStatus status = dimension.status();
        if (falling(scores) && status == QualityStatus.PASS) {
            status = QualityStatus.WARN;
            facts.add("score fell in each of the last " + FALLING_RUNS + " runs");
        }
        return new QualityDimension(dimension.name(), status, dimension.score(), dimension.formula(), facts,
                dimension.issues(), dimension.delta());
    }

    private static boolean falling(List<Integer> scores) {
        if (scores.size() < FALLING_RUNS + 1) {
            return false;
        }
        for (int i = scores.size() - FALLING_RUNS; i < scores.size(); i++) {
            if (scores.get(i) >= scores.get(i - 1)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Suggests promoting the baseline when the last runs were steady and the baseline is behind —
     * issues it holds are gone, or there is none. Only says so: the baseline still changes only
     * through {@code writeBaseline()}.
     */
    static List<String> baselineNotes(QualityReport report, QualityHistory history, boolean hasBaseline,
                                      int steadyRuns) {
        List<QualityHistory.Entry> entries = history.entries();
        if (entries.size() < steadyRuns - 1 || report.overall() == QualityStatus.FAIL
                || report.overall() == QualityStatus.NOT_MEASURED) {
            return List.of();
        }
        boolean steady = entries.subList(entries.size() - (steadyRuns - 1), entries.size()).stream()
                .allMatch(e -> QualityStatus.PASS.name().equals(e.overall())
                        || QualityStatus.WARN.name().equals(e.overall()));
        if (!steady) {
            return List.of();
        }
        if (!hasBaseline) {
            return List.of("the last " + steadyRuns + " runs had no FAIL and there is no baseline: writeBaseline() "
                    + "in a reviewed change would start comparing against this run");
        }
        int resolved = report.dimensions().stream().filter(d -> d.delta() != null)
                .mapToInt(d -> d.delta().resolvedIssues().size()).sum();
        return resolved == 0 ? List.of() : List.of("the last " + steadyRuns + " runs had no FAIL and " + resolved
                + " baseline issues are resolved: writeBaseline() in a reviewed change would promote this run");
    }

    static double median(long[] values) {
        return median(Arrays.stream(values).asDoubleStream().toArray());
    }

    static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
    }

    static double mad(long[] values, double median) {
        return median(Arrays.stream(values).mapToDouble(v -> Math.abs(v - median)).toArray());
    }
}
