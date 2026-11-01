package io.testforge.quality;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Where a quality assessment reads a finished run and what it may fail on.
 *
 * <pre>
 * forge:
 *   quality:
 *     enabled: true                       # assertAcceptable() fails closed without it
 *     junit-results: [build/test-results] # JUnit XML, searched recursively
 *     artifacts-root: build/testforge-artifacts
 *     run-id:                             # default: the newest run; "a,b" merges runs of several JVMs
 *     output-dir: build/quality
 *     max-flaky-rate: 0.02                # stability FAILs above it
 *     fail-on: [functional, stability]    # dimensions whose FAIL fails the gate
 *     baseline-file: build/quality/baseline/quality-baseline.json
 *     accepted:                           # known issues the gate lets through, with a reason
 *       - "functional io.acme.OrdersTest.refunds()  # ORD-12, fixed in 2.4"
 *     efficiency:
 *       slowdown-factor: 2.0              # a test this many times slower than in the baseline…
 *       min-millis: 1000                  # …and at least this much slower counts
 *       mad-factor: 3.0                   # with history: slower than median + this × MAD (and min-millis)
 *       min-latency-millis: 100           # with history: an operation's p95 this much above its median
 *     required: [functional, contracts]   # the gate fails while one of these is NOT MEASURED
 *     min-api-coverage: 0.8               # api-coverage FAILs below this share of documented operations
 *     results-not-before: 2026-11-01T12:00:00Z  # JUnit suites older than this are stale, dropped
 *     history:
 *       dir: build/quality/history        # one entry per run; off without it
 *       window: 10                        # earlier runs read for stability, efficiency and trends
 *       keep: 30                          # entries kept in dir; the oldest beyond it are dropped
 *       min-runs: 3                       # runs a test needs in the window before its median counts
 *       revision:                         # the commit tested; default GITHUB_SHA, CI_COMMIT_SHA, GIT_COMMIT
 *     run-labels:                         # added to quality.json, history and metrics (branch, job, revision
 *       environment: staging              #   come from the CI's own variables)
 *     components:                         # issues by owner: the first component whose pattern matches the key
 *       orders:
 *         owner: team-orders
 *         match: ["io.acme.orders.*", "* /api/v1/orders*", "*ORDERS*"]
 *     gate:                               # per dimension, on top of fail-on
 *       stability: { max-new-issues: 2 }  # a budget: fail-on fails only beyond it
 *       api-coverage: { min-score: 80 }   # fails below the score, whatever the status
 * </pre>
 *
 * An accepted entry may expire: {@code "functional X  # until 2026-12-01, ORD-12"} counts as new
 * from that day on.
 *
 * With a baseline the gate fails only on issues the baseline did not have;
 * {@code accepted} lets a known issue through without a new baseline.
 *
 * It reads what other jobs left behind, so it runs after them — usually as a
 * JUnit test in its own CI step — and never re-runs a test.
 */
@ConfigurationProperties(prefix = "forge.quality")
public record QualityProperties(
        boolean enabled,
        List<String> junitResults,
        String artifactsRoot,
        String runId,
        String outputDir,
        Double maxFlakyRate,
        List<String> failOn,
        String baselineFile,
        List<String> accepted,
        EfficiencyProperties efficiency,
        List<String> required,
        java.time.Instant resultsNotBefore,
        Double minApiCoverage,
        HistoryProperties history,
        java.util.Map<String, String> runLabels,
        java.util.Map<String, ComponentProperties> components,
        java.util.Map<String, GateProperties> gate,
        java.util.Map<String, java.time.LocalDate> acceptedUntil) {

    @ConstructorBinding
    public QualityProperties {
        junitResults = junitResults == null || junitResults.isEmpty() ? List.of("build/test-results")
                : List.copyOf(junitResults);
        if (artifactsRoot == null || artifactsRoot.isBlank()) {
            artifactsRoot = "build/testforge-artifacts";
        }
        if (runId != null && runId.isBlank()) {
            runId = null;
        }
        if (outputDir == null || outputDir.isBlank()) {
            outputDir = "build/quality";
        }
        if (maxFlakyRate == null || maxFlakyRate < 0) {
            maxFlakyRate = 0.02;
        }
        failOn = failOn == null || failOn.isEmpty() ? List.of(QualityDimension.FUNCTIONAL, QualityDimension.STABILITY)
                : List.copyOf(failOn);
        if (baselineFile == null || baselineFile.isBlank()) {
            baselineFile = "build/quality/baseline/quality-baseline.json";
        }
        java.util.Map<String, java.time.LocalDate> until = new java.util.LinkedHashMap<>(
                acceptedUntil == null ? java.util.Map.of() : acceptedUntil);
        if (accepted != null) {
            for (String entry : accepted) {
                String[] parts = entry == null ? new String[] {""} : entry.split(" #", 2);
                java.util.regex.Matcher date = parts.length < 2 ? null : UNTIL.matcher(parts[1]);
                if (date != null && date.find()) {
                    until.put(parts[0].strip().replaceAll("\\s+", " "), java.time.LocalDate.parse(date.group(1)));
                }
            }
        }
        acceptedUntil = java.util.Map.copyOf(until);
        accepted = accepted == null ? List.of() : accepted.stream()
                .map(entry -> entry == null ? "" : entry.split(" #", 2)[0].strip().replaceAll("\\s+", " "))
                .filter(entry -> !entry.isEmpty())
                .distinct()
                .toList();
        if (efficiency == null) {
            efficiency = new EfficiencyProperties(null, null);
        }
        required = required == null ? List.of() : List.copyOf(required);
        if (minApiCoverage != null && (minApiCoverage < 0 || minApiCoverage > 1)) {
            throw new IllegalArgumentException("forge.quality.min-api-coverage is a share between 0 and 1");
        }
        if (history == null) {
            history = new HistoryProperties(null, null, null, null, null);
        }
        runLabels = runLabels == null ? java.util.Map.of() : java.util.Map.copyOf(runLabels);
        // binding keeps the declared order; the first matching component wins
        components = components == null ? java.util.Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(components));
        gate = gate == null ? java.util.Map.of() : java.util.Map.copyOf(gate);
    }

    private static final java.util.regex.Pattern UNTIL =
            java.util.regex.Pattern.compile("\\buntil\\s+(\\d{4}-\\d{2}-\\d{2})");

    public QualityProperties(boolean enabled, List<String> junitResults, String artifactsRoot, String runId,
                             String outputDir, Double maxFlakyRate, List<String> failOn, String baselineFile,
                             List<String> accepted, EfficiencyProperties efficiency, List<String> required,
                             java.time.Instant resultsNotBefore, Double minApiCoverage, HistoryProperties history) {
        this(enabled, junitResults, artifactsRoot, runId, outputDir, maxFlakyRate, failOn, baselineFile, accepted,
                efficiency, required, resultsNotBefore, minApiCoverage, history, null, null, null, null);
    }

    /** Who owns the issues whose key matches one of {@code match} ({@code *} is any text). */
    public record ComponentProperties(String owner, List<String> match) {

        public ComponentProperties {
            match = match == null ? List.of() : List.copyOf(match);
        }
    }

    /**
     * A dimension's own gate: fail below {@code minScore}; with {@code maxNewIssues}, a
     * {@code fail-on} FAIL passes while its new issues stay within the budget, and any status
     * fails beyond it.
     */
    public record GateProperties(Integer minScore, Integer maxNewIssues) {
    }

    public QualityProperties(boolean enabled, List<String> junitResults, String artifactsRoot, String runId,
                             String outputDir, Double maxFlakyRate, List<String> failOn, String baselineFile,
                             List<String> accepted, EfficiencyProperties efficiency, List<String> required,
                             java.time.Instant resultsNotBefore, Double minApiCoverage) {
        this(enabled, junitResults, artifactsRoot, runId, outputDir, maxFlakyRate, failOn, baselineFile, accepted,
                efficiency, required, resultsNotBefore, minApiCoverage, null);
    }

    public QualityProperties(boolean enabled, List<String> junitResults, String artifactsRoot, String runId,
                             String outputDir, Double maxFlakyRate, List<String> failOn, String baselineFile,
                             List<String> accepted, EfficiencyProperties efficiency, List<String> required,
                             java.time.Instant resultsNotBefore) {
        this(enabled, junitResults, artifactsRoot, runId, outputDir, maxFlakyRate, failOn, baselineFile, accepted,
                efficiency, required, resultsNotBefore, null);
    }

    public QualityProperties(boolean enabled, List<String> junitResults, String artifactsRoot, String runId,
                             String outputDir, Double maxFlakyRate, List<String> failOn, String baselineFile,
                             List<String> accepted, EfficiencyProperties efficiency) {
        this(enabled, junitResults, artifactsRoot, runId, outputDir, maxFlakyRate, failOn, baselineFile, accepted,
                efficiency, null, null, null);
    }

    /**
     * When a test counts as slower: against the baseline, both {@code slowdownFactor} and
     * {@code minMillis}; against the history, {@code madFactor} and {@code minMillis}. An API
     * operation's p95 (history only): {@code madFactor} and {@code minLatencyMillis}.
     */
    public record EfficiencyProperties(Double slowdownFactor, Long minMillis, Double madFactor,
                                       Long minLatencyMillis) {

        @ConstructorBinding
        public EfficiencyProperties {
            if (slowdownFactor == null || slowdownFactor < 1) {
                slowdownFactor = 2.0;
            }
            if (minMillis == null || minMillis < 0) {
                minMillis = 1000L;
            }
            if (madFactor == null || madFactor <= 0) {
                madFactor = 3.0;
            }
            if (minLatencyMillis == null || minLatencyMillis < 0) {
                minLatencyMillis = 100L;
            }
        }

        public EfficiencyProperties(Double slowdownFactor, Long minMillis) {
            this(slowdownFactor, minMillis, null, null);
        }
    }

    /**
     * The last runs, for what one run cannot tell: flakiness on unchanged code, durations against
     * their median, score trends. Off while {@code dir} is unset.
     */
    public record HistoryProperties(String dir, Integer window, Integer keep, Integer minRuns, String revision) {

        public HistoryProperties {
            if (dir != null && dir.isBlank()) {
                dir = null;
            }
            if (window == null || window < 1) {
                window = 10;
            }
            if (keep == null || keep < 1) {
                keep = Math.max(30, window);
            }
            if (minRuns == null || minRuns < 1) {
                minRuns = 3;
            }
            if (revision != null && revision.isBlank()) {
                revision = null;
            }
        }

        public boolean enabled() {
            return dir != null;
        }
    }

    public QualityProperties(boolean enabled, List<String> junitResults, String artifactsRoot, String runId,
                             String outputDir, Double maxFlakyRate, List<String> failOn, String baselineFile,
                             List<String> accepted) {
        this(enabled, junitResults, artifactsRoot, runId, outputDir, maxFlakyRate, failOn, baselineFile, accepted,
                null);
    }

    public QualityProperties(boolean enabled, List<String> junitResults, String artifactsRoot, String runId,
                             String outputDir, Double maxFlakyRate, List<String> failOn) {
        this(enabled, junitResults, artifactsRoot, runId, outputDir, maxFlakyRate, failOn, null, null, null);
    }
}
