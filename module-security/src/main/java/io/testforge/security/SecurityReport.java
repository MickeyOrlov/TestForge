package io.testforge.security;

import java.util.List;
import java.util.Map;

public record SecurityReport(
        int formatVersion,
        boolean enabled,
        boolean measured,
        boolean complete,
        boolean healthy,
        List<SecurityFinding> findings,
        List<SecurityCoverage> coverage,
        Map<String, Long> newCounts) {
    public SecurityReport {
        findings = List.copyOf(findings);
        coverage = List.copyOf(coverage);
        newCounts = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(newCounts));
    }
}
