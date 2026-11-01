package io.testforge.quality;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
 * </pre>
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
        List<String> failOn) {

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
    }
}
