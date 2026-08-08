package io.testforge.api.explorer;

/**
 * Renders the report a person actually reads.
 *
 * <p>Ordered so the first screen answers the question someone new to the API
 * has: what works, what lies, what nobody could call — and for the last group,
 * what to configure so the next run can.
 */
final class ExplorerReportMarkdown {

    private ExplorerReportMarkdown() {
    }

    static String render(ApiExplorerReport report) {
        StringBuilder out = new StringBuilder("# API Explorer Report\n\n");
        out.append("- generated: ").append(report.generatedAt()).append('\n');
        out.append("- enabled: ").append(report.enabled()).append('\n');
        out.append("- healthy: ").append(report.healthy()).append('\n');

        if (!report.enabled()) {
            out.append("\nExploration is disabled (`forge.api-explorer.enabled`).\n");
            return out.toString();
        }
        if (report.specs().isEmpty()) {
            out.append("\nNo specs configured under `forge.api-discovery.specs`.\n");
            return out.toString();
        }

        report.specs().forEach(spec -> renderSpec(out, spec));
        return out.toString();
    }

    private static void renderSpec(StringBuilder out, SpecExplorationReport spec) {
        out.append("\n## ").append(spec.specId()).append("\n\n");
        out.append("- spec: ").append(spec.location()).append('\n');
        out.append("- base URL: ").append(spec.baseUrl()).append('\n');

        if (spec.error() != null) {
            out.append("- error: ").append(spec.error()).append('\n');
            return;
        }

        out.append("- operations: ").append(spec.operations())
                .append(" (passed ").append(spec.passed())
                .append(", contract mismatches ").append(spec.contractMismatch())
                .append(", failed ").append(spec.failedCalls())
                .append(", skipped ").append(spec.skipped())
                .append(")\n\n");

        out.append("| operation | outcome | status | ms | notes |\n");
        out.append("|---|---|---|---|---|\n");
        spec.observations().forEach(observation -> out
                .append("| `").append(observation.key()).append("` | ")
                .append(observation.outcome()).append(" | ")
                .append(observation.status() == null ? "-" : observation.status()).append(" | ")
                .append(observation.durationMillis() == null ? "-" : observation.durationMillis()).append(" | ")
                .append(notes(observation)).append(" |\n"));

        renderSkipped(out, spec);
    }

    private static String notes(ObservationSummary observation) {
        if (observation.outcome() == ExplorerOutcome.SKIPPED) {
            return observation.reason() == null ? "-" : observation.reason();
        }
        if (observation.mismatches() > 0) {
            return observation.mismatches() + " mismatch(es)";
        }
        return observation.reason() == null ? "-" : observation.reason();
    }

    /**
     * The actionable half of the report: every operation the run could not
     * call, with the reason. A team reads this and knows exactly which
     * identifiers to put into configuration.
     */
    private static void renderSkipped(StringBuilder out, SpecExplorationReport spec) {
        var skipped = spec.observations().stream()
                .filter(observation -> observation.skipReason() == SkipReason.MISSING_PATH_PARAMETER
                        || observation.skipReason() == SkipReason.MISSING_REQUIRED_QUERY_PARAMETER)
                .toList();
        if (skipped.isEmpty()) {
            return;
        }

        out.append("\n### Supply these to explore more\n\n```yaml\nforge:\n  api-explorer:\n    parameters:\n"
                + "      operations:\n");
        skipped.forEach(observation -> out
                .append("        ").append(observation.operationId()).append(":\n")
                .append("          # ").append(observation.reason()).append('\n'));
        out.append("```\n");
    }
}
