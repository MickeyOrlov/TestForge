package io.testforge.api.fuzz;

import io.testforge.api.fuzz.ApiFuzzReport.SpecFuzzReport;
import java.util.List;

/**
 * Renders findings first and everything else after.
 *
 * <p>A fuzz run produces hundreds of uninteresting results and a handful that
 * matter. A report that lists them in execution order buries the handful, so
 * this one leads with the findings and prints, next to each, the exact
 * configuration that repeats that single request.
 */
final class FuzzReportMarkdown {

    private FuzzReportMarkdown() {
    }

    static String render(ApiFuzzReport report) {
        StringBuilder out = new StringBuilder("# API Fuzz Report\n\n");
        out.append("- generated: ").append(report.generatedAt()).append('\n');
        out.append("- enabled: ").append(report.enabled()).append('\n');
        out.append("- seed: ").append(report.seed()).append('\n');
        out.append("- healthy: ").append(report.healthy()).append('\n');

        if (!report.enabled()) {
            out.append("\nFuzzing is disabled (`forge.api-fuzz.enabled`).\n");
            return out.toString();
        }
        if (report.specs().isEmpty()) {
            out.append("\nNo specs configured under `forge.api-discovery.specs`.\n");
            return out.toString();
        }

        renderFindings(out, report);
        report.specs().forEach(spec -> renderSpec(out, spec));
        return out.toString();
    }

    private static void renderFindings(StringBuilder out, ApiFuzzReport report) {
        List<FuzzObservation> findings = report.findings();
        out.append("\n## Findings (").append(findings.size()).append(")\n\n");

        if (findings.isEmpty()) {
            out.append("Every case behaved the way the document implies.\n");
            return;
        }

        // expectation is in the table on purpose: a verdict of OVER_PERMISSIVE
        // only means anything next to the promise the document made
        out.append("| verdict | operation | field | mutation | expected | status | sent | case id |\n");
        out.append("|---|---|---|---|---|---|---|---|\n");
        findings.forEach(finding -> out
                .append("| **").append(finding.verdict()).append("** | `")
                .append(finding.fuzzCase().operationKey()).append("` | `")
                .append(finding.fuzzCase().in()).append(':').append(finding.fuzzCase().parameterName())
                .append("` | ").append(finding.fuzzCase().kind())
                .append(" | ").append(finding.expectation())
                .append(" | ").append(finding.status() == null ? "-" : finding.status())
                .append(" | `").append(escape(finding.requestFragment()))
                .append("` | `").append(finding.fuzzCase().id()).append("` |\n"));

        out.append("\n### Reproduce a single case\n\n```yaml\nforge:\n  api-fuzz:\n    seed: ")
                .append(report.seed())
                .append("\n    only-cases:\n");
        findings.forEach(finding -> out.append("      - \"").append(finding.fuzzCase().id()).append("\"\n"));
        out.append("```\n");
    }

    /** Keeps a mutated value from breaking the table it is reported in. */
    private static String escape(String fragment) {
        if (fragment == null) {
            return "-";
        }
        String flattened = fragment.replace("\n", "\\n").replace("\r", "").replace("|", "\\|");
        return flattened.length() <= 60 ? flattened : flattened.substring(0, 60) + "…";
    }

    private static void renderSpec(StringBuilder out, SpecFuzzReport spec) {
        out.append("\n## ").append(spec.specId()).append("\n\n");
        out.append("- spec: ").append(spec.location()).append('\n');
        out.append("- base URL: ").append(spec.baseUrl()).append('\n');

        if (spec.error() != null) {
            out.append("- error: ").append(spec.error()).append('\n');
            return;
        }

        out.append("- operations: ").append(spec.operationCount())
                .append(", cases: ").append(spec.cases())
                .append(", findings: ").append(spec.findings())
                .append("\n\n");

        out.append("| operation | cases | findings | note |\n");
        out.append("|---|---|---|---|\n");
        spec.operations().forEach(operation -> out
                .append("| `").append(operation.operationKey()).append("` | ")
                .append(operation.cases()).append(" | ")
                .append(operation.findings()).append(" | ")
                .append(operation.skipReason() == null ? "-" : operation.skipReason())
                .append(" |\n"));
    }
}
