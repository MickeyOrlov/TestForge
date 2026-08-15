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
    /**
     * Redaction lives in the canonical constructor deliberately. Every
     * construction path — including future ones, and including tests — then
     * redacts, so there is no way to build an evidence record that carries
     * credentials into an artifact. Doing it at the call sites instead would
     * make the guarantee depend on remembering it.
     */
    public FuzzRunEvidence {
        targetUrl = UrlRedactor.redact(targetUrl);
    }
}
