package io.testforge.api.explorer;

import java.util.List;

/** The whole matrix of one run, as written to {@code access-matrix.json}. */
public record AccessMatrixReport(
        String generatedAt,
        String baseUrl,
        List<String> roles,
        boolean healthy,
        int violations,
        int errors,
        int undeclared,
        int skipped,
        List<AccessRow> rows,
        String reportJson,
        String reportMarkdown) {

    public AccessMatrixReport {
        roles = List.copyOf(roles);
        rows = List.copyOf(rows);
    }

    /** Every cell that broke its rule, in matrix order. */
    public List<String> violationLines() {
        return rows.stream()
                .flatMap(row -> row.cells().stream()
                        .filter(cell -> cell.verdict() == AccessCell.Verdict.VIOLATION)
                        .map(cell -> row.key() + " (" + row.operationId() + ") as " + cell.role()
                                + ": got " + cell.status() + ", expected " + cell.expected()))
                .toList();
    }
}
