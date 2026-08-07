package io.testforge.api.explorer;

import java.util.List;

/**
 * The result of one exploration run: a map of what the API actually does,
 * next to what its documents say it does.
 */
public record ApiExplorerReport(
        boolean enabled,
        String generatedAt,
        boolean healthy,
        List<SpecExplorationReport> specs,
        String outputDir,
        String reportJson,
        String reportMarkdown) {

    public ApiExplorerReport {
        specs = List.copyOf(specs == null ? List.of() : specs);
    }

    public List<SpecExplorationReport> failingSpecs() {
        return specs.stream().filter(SpecExplorationReport::failed).toList();
    }
}
