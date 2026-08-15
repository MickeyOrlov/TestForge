package io.testforge.api.fuzz;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The reproduction contract. This record captures exactly what a human needs to rerun a finding.
 */
public record FuzzRunEvidence(
        String runId,
        String specId,
        String specLocation,
        String targetUrl,
        String schemathesisVersion,
        long seed,
        List<String> phases,
        Set<String> methods,
        String generationMode,
        int maxExamples,
        boolean allowUnsafeMethods,
        int exitCode,
        String outcome,
        Map<String, String> artifacts,
        Instant startedAt,
        Duration duration
) {
    public FuzzRunEvidence {
        targetUrl = UrlRedactor.redact(targetUrl);
    }
}
