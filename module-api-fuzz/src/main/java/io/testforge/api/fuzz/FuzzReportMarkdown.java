package io.testforge.api.fuzz;

import io.testforge.api.fuzz.ApiFuzzReport.SpecFuzzReport;
import java.util.List;
import java.util.Map;

/**
 * Renders findings first, then what could not be concluded, then what was
 * never tested at all.
 *
 * <p>That last section is the one most fuzz reports leave out. A page of green
 * results means nothing without knowing which of the document's promises were
 * actually exercised, and an operation whose control request was refused
 * produces green-looking rows for reasons that have nothing to do with the
 * service being correct.
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
        renderFlaky(out, report);
        renderInconclusive(out, report);
        report.specs().forEach(spec -> renderSpec(out, spec));
        return out.toString();
    }

    private static void renderFindings(StringBuilder out, ApiFuzzReport report) {
        List<FuzzObservation> findings = report.findings();
        out.append("\n## Findings (").append(findings.size()).append(")\n\n");

        if (findings.isEmpty()) {
            out.append("Every conclusive case behaved the way the document implies.\n");
            return;
        }

        out.append("| verdict | evidence | operation | field | mutation | expected | control | fuzz "
                + "| reproducibility | shrink | case id |\n");
        out.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        findings.forEach(finding -> out
                .append("| **").append(finding.verdict()).append("** | ")
                .append(evidence(finding)).append(" | `")
                .append(finding.fuzzCase().operationKey()).append("` | `")
                .append(finding.fuzzCase().location()).append("` | ")
                .append(finding.fuzzCase().kind())
                .append(" | ").append(finding.expectation())
                .append(" | ").append(controlStatus(report, finding))
                .append(" | ").append(finding.status() == null ? "-" : finding.status())
                .append(" | ").append(finding.confirmation().summary())
                .append(" | ").append(finding.shrink().summary())
                .append(" | `").append(finding.fuzzCase().id()).append("` |\n"));

        out.append("\nEvery finding has a folder under `reproductions/` with the minimal request, what the "
                + "document promised, and the snippet that runs it again.\n");

        out.append("\n### Reproduce a single case\n\n```yaml\nforge:\n  api-fuzz:\n    seed: ")
                .append(report.seed())
                .append("\n    only-cases:\n");
        findings.forEach(finding -> out.append("      - \"").append(finding.fuzzCase().id()).append("\"\n"));
        out.append("```\n");
        out.append("\nFull manifests, including the spec fingerprint each finding was made against, are in "
                + "`<spec>/reproduction.json`.\n");
    }

    /**
     * Findings whose response varied between identical requests. Kept in the
     * report rather than dropped: an intermittent 500 is usually the most
     * interesting one there is, and hiding it because it did not repeat cleanly
     * is how a fuzzer becomes reassuring instead of useful.
     */
    private static void renderFlaky(StringBuilder out, ApiFuzzReport report) {
        List<FuzzObservation> flaky = report.findings().stream()
                .filter(FuzzObservation::flaky)
                .toList();
        if (flaky.isEmpty()) {
            return;
        }

        out.append("\n## Flaky findings (").append(flaky.size()).append(")\n\n");
        out.append("| operation | field | mutation | seen | case id |\n");
        out.append("|---|---|---|---|---|\n");
        flaky.forEach(finding -> out
                .append("| `").append(finding.fuzzCase().operationKey()).append("` | `")
                .append(finding.fuzzCase().location()).append("` | ")
                .append(finding.fuzzCase().kind()).append(" | ")
                .append(finding.confirmation().matches()).append('/')
                .append(finding.confirmation().attempts()).append(" | `")
                .append(finding.fuzzCase().id()).append("` |\n"));
    }

    /**
     * The cases the run could not draw a conclusion from. Reported separately
     * because they are neither successes nor defects — they are the run saying
     * what it could not see, which is the difference between an honest report
     * and a reassuring one.
     */
    private static void renderInconclusive(StringBuilder out, ApiFuzzReport report) {
        List<FuzzObservation> inconclusive = report.inconclusive();
        if (inconclusive.isEmpty()) {
            return;
        }

        out.append("\n## Inconclusive (").append(inconclusive.size()).append(")\n\n");
        out.append("| operation | field | mutation | status | why |\n");
        out.append("|---|---|---|---|---|\n");
        inconclusive.forEach(observation -> out
                .append("| `").append(observation.fuzzCase().operationKey()).append("` | `")
                .append(observation.fuzzCase().location()).append("` | ")
                .append(observation.fuzzCase().kind()).append(" | ")
                .append(observation.status() == null ? "-" : observation.status()).append(" | ")
                .append(observation.reason() == null ? "-" : observation.reason()).append(" |\n"));
    }

    private static void renderSpec(StringBuilder out, SpecFuzzReport spec) {
        out.append("\n## ").append(spec.specId()).append("\n\n");
        out.append("- spec: ").append(spec.location()).append('\n');
        out.append("- fingerprint: ").append(spec.fingerprint()).append('\n');
        out.append("- base URL: ").append(spec.baseUrl()).append('\n');

        if (spec.error() != null) {
            out.append("- error: ").append(spec.error()).append('\n');
            return;
        }

        out.append("- operations: ").append(spec.operationCount())
                .append(", cases: ").append(spec.cases())
                .append(", findings: ").append(spec.findings())
                .append("\n");

        spec.operations().forEach(operation -> renderOperation(out, operation));
    }

    private static void renderOperation(StringBuilder out, OperationFuzzReport operation) {
        out.append("\n### ").append(operation.operationKey()).append("\n\n");
        out.append("- control: ").append(control(operation)).append('\n');

        if (operation.skipReason() != null) {
            out.append("- skipped: ").append(operation.skipReason()).append('\n');
        } else {
            out.append("- cases: ").append(operation.cases())
                    .append(", findings: ").append(operation.findings())
                    .append(", inconclusive: ").append(operation.inconclusive())
                    .append('\n');
        }

        renderCoverage(out, operation.coverage());
    }

    /**
     * Constraint coverage, listed rather than scored. A percentage would invite
     * comparing APIs that declare wildly different amounts, and would reward a
     * vague document for being vague.
     *
     * <p>Four layers, and the third is the one worth reading: a constraint
     * nothing could honestly attack is a different problem from one the case
     * budget did not reach, and only the reason distinguishes them.
     */
    private static void renderCoverage(StringBuilder out, ConstraintCoverage coverage) {
        if (coverage.declared().isEmpty() && coverage.outcomes().total() == 0) {
            return;
        }

        out.append("- constraints: ").append(coverage.declared().size())
                .append(" declared, ").append(coverage.exercised().size()).append(" exercised, ")
                .append(coverage.unsupported().size()).append(" unsupported, ")
                .append(coverage.unexercised().size()).append(" not exercised\n");

        if (!coverage.exercised().isEmpty()) {
            out.append("\n  exercised:\n");
            coverage.exercised().forEach(constraint -> out.append("  - ").append(constraint).append('\n'));
        }
        if (!coverage.unsupported().isEmpty()) {
            out.append("\n  unsupported — no mutation here could be proven invalid:\n");
            coverage.unsupported().forEach(constraint -> out.append("  - ").append(constraint).append('\n'));
        }
        if (!coverage.unexercised().isEmpty()) {
            out.append("\n  not exercised:\n");
            coverage.unexercised().forEach(constraint -> out.append("  - ").append(constraint).append('\n'));
        }
        renderOutcomes(out, coverage.outcomes());
    }

    /** Raw counts. Anything averaged into a single figure would hide a crash. */
    private static void renderOutcomes(StringBuilder out, MutationOutcomes outcomes) {
        if (outcomes.total() == 0) {
            return;
        }

        out.append("\n  mutation outcomes: ").append(outcomes.schemaMutations()).append(" schema, ")
                .append(outcomes.protocolMutations()).append(" protocol\n");
        counts(out, "  - expectation ", outcomes.byExpectation());
        counts(out, "  - verdict ", outcomes.byVerdict());
        counts(out, "  - evidence ", outcomes.byEvidence());
    }

    private static void counts(StringBuilder out, String prefix, Map<?, Integer> counts) {
        counts.forEach((key, count) -> out.append(prefix).append(key).append(": ").append(count).append('\n'));
    }

    private static String control(OperationFuzzReport operation) {
        ControlResult control = operation.control();
        if (control == null) {
            return "not sent";
        }
        String status = control.status() == null ? "-" : String.valueOf(control.status());
        return control.reason() == null
                ? "%s %s".formatted(status, control.outcome())
                : "%s %s — %s".formatted(status, control.outcome(), control.reason());
    }

    private static String controlStatus(ApiFuzzReport report, FuzzObservation finding) {
        return report.specs().stream()
                .flatMap(spec -> spec.operations().stream())
                .filter(operation -> operation.operationKey().equals(finding.fuzzCase().operationKey()))
                .findFirst()
                .map(operation -> operation.control() == null || operation.control().status() == null
                        ? "-" : String.valueOf(operation.control().status()))
                .orElse("-");
    }

    private static String evidence(FuzzObservation finding) {
        List<String> kinds = finding.evidence().stream()
                .filter(item -> item.kind().reportable())
                .map(item -> item.kind().toString())
                .toList();
        return kinds.isEmpty() ? "-" : String.join(", ", kinds);
    }

    /** Keeps a mutated value from breaking the table it is reported in. */
    private static String escape(String fragment) {
        if (fragment == null) {
            return "-";
        }
        String flattened = fragment.replace("\n", "\\n").replace("\r", "").replace("|", "\\|");
        return flattened.length() <= 60 ? flattened : flattened.substring(0, 60) + "…";
    }
}
