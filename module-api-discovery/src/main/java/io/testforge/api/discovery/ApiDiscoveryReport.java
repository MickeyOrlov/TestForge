package io.testforge.api.discovery;

import java.util.List;

public record ApiDiscoveryReport(
        boolean enabled,
        String generatedAt,
        boolean healthy,
        List<ApiSpecReport> specs,
        String outputDir,
        String reportJson,
        String reportMarkdown) {

    public ApiDiscoveryReport {
        specs = List.copyOf(specs == null ? List.of() : specs);
    }

    public List<ApiSpecReport> failingSpecs() {
        return specs.stream()
                .filter(ApiSpecReport::failed)
                .toList();
    }
}
