package io.testforge.api.explorer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The matrix a person reads: violations first, then the table, then the
 * expectations the run would propose for whatever nobody declared yet.
 */
final class AccessMatrixMarkdown {

    private AccessMatrixMarkdown() {
    }

    static String render(AccessMatrixReport report) {
        StringBuilder out = new StringBuilder("# Access Matrix\n\n");
        out.append("- generated: ").append(report.generatedAt()).append('\n');
        out.append("- base URL: ").append(report.baseUrl()).append('\n');
        out.append("- roles: ").append(String.join(", ", report.roles())).append('\n');
        out.append("- healthy: ").append(report.healthy()).append('\n');
        out.append("- violations: ").append(report.violations())
                .append(", errors: ").append(report.errors())
                .append(", undeclared: ").append(report.undeclared())
                .append(", skipped: ").append(report.skipped()).append('\n');

        List<String> violations = report.violationLines();
        if (!violations.isEmpty()) {
            out.append("\n## Violations\n\n");
            violations.forEach(line -> out.append("- ").append(line).append('\n'));
        }

        out.append("\n## Matrix\n\n| Operation |");
        report.roles().forEach(role -> out.append(' ').append(role).append(" |"));
        out.append("\n|---|");
        report.roles().forEach(role -> out.append("---|"));
        out.append('\n');
        for (AccessRow row : report.rows()) {
            out.append("| `").append(row.key()).append("` ").append(row.operationId()).append(" |");
            row.cells().forEach(cell -> out.append(' ').append(cell(cell)).append(" |"));
            out.append('\n');
        }

        String proposal = proposal(report);
        if (!proposal.isEmpty()) {
            out.append("\n## Proposed expectations\n\n")
                    .append("What this run observed for undeclared cells. Review before pasting: ")
                    .append("an observed `allow` may be the very hole the matrix exists to find.\n\n")
                    .append("```yaml\nforge:\n  access-matrix:\n").append(proposal).append("```\n");
        }
        return out.toString();
    }

    private static String cell(AccessCell cell) {
        return switch (cell.verdict()) {
            case OK -> cell.status() + " ok";
            case VIOLATION -> "**" + cell.status() + " VIOLATION** (expected " + cell.expected() + ")";
            case UNDECLARED -> cell.status() + " ?";
            case ERROR -> "error" + (cell.status() > 0 ? " " + cell.status() : "") + ": " + cell.detail();
            case SKIPPED -> "skipped: " + cell.detail();
        };
    }

    /**
     * Per role, the most common observation becomes its {@code expect} line and
     * the operations that differ get their own; a 5xx or failed call proposes nothing.
     */
    static String proposal(AccessMatrixReport report) {
        Map<String, String> expect = new TreeMap<>();
        Map<String, Map<String, String>> operations = new TreeMap<>();
        for (String role : report.roles()) {
            Map<String, String> observed = new LinkedHashMap<>();
            for (AccessRow row : report.rows()) {
                row.cells().stream()
                        .filter(cell -> cell.role().equals(role) && cell.verdict() == AccessCell.Verdict.UNDECLARED)
                        .filter(cell -> cell.status() > 0 && cell.status() < 500)
                        .forEach(cell -> observed.put(row.operationId(), AccessRule.describe(cell.status())));
            }
            if (observed.isEmpty()) {
                continue;
            }
            String common = observed.values().stream()
                    .collect(Collectors.groupingBy(Function.identity(), TreeMap::new, Collectors.counting()))
                    .entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
            expect.put(role, common);
            observed.forEach((operationId, rule) -> {
                if (!rule.equals(common)) {
                    operations.computeIfAbsent(operationId, ignored -> new TreeMap<>()).put(role, rule);
                }
            });
        }
        if (expect.isEmpty()) {
            return "";
        }
        StringBuilder yaml = new StringBuilder("    expect:\n");
        expect.forEach((role, rule) -> yaml.append("      ").append(role).append(": ").append(rule).append('\n'));
        if (!operations.isEmpty()) {
            yaml.append("    operations:\n");
            operations.forEach((operationId, rules) -> {
                yaml.append("      ").append(operationId).append(":\n");
                rules.forEach((role, rule) -> yaml.append("        ").append(role).append(": ").append(rule).append('\n'));
            });
        }
        return yaml.toString();
    }
}
