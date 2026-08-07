package io.testforge.api.codegen;

import java.util.List;

public record ApiCodegenReport(
        boolean enabled,
        String generatedAt,
        boolean healthy,
        String outputDir,
        List<ApiCodegenSpecReport> specs,
        String error,
        String reportJson,
        String reportMarkdown) {

    public ApiCodegenReport {
        specs = List.copyOf(specs == null ? List.of() : specs);
    }

    public List<ApiCodegenSpecReport> failingSpecs() {
        return specs.stream().filter(ApiCodegenSpecReport::failed).toList();
    }
}
