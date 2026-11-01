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
 *     run-id:                             # default: the newest run with a manifest
 *     output-dir: build/quality
 *     max-flaky-rate: 0.02                # stability FAILs above it
 *     fail-on: [functional, stability]    # dimensions whose FAIL fails the gate
 *     baseline-file: build/quality/baseline/quality-baseline.json
 *     accepted:                           # known issues the gate lets through, with a reason
 *       - "functional io.acme.OrdersTest.refunds()  # ORD-12, fixed in 2.4"
 * </pre>
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
        List<String> accepted) {

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
        accepted = accepted == null ? List.of() : accepted.stream()
                .map(entry -> entry == null ? "" : entry.split(" #", 2)[0].strip().replaceAll("\\s+", " "))
                .filter(entry -> !entry.isEmpty())
                .distinct()
                .toList();
    }

    public QualityProperties(boolean enabled, List<String> junitResults, String artifactsRoot, String runId,
                             String outputDir, Double maxFlakyRate, List<String> failOn) {
        this(enabled, junitResults, artifactsRoot, runId, outputDir, maxFlakyRate, failOn, null, null);
    }
}
