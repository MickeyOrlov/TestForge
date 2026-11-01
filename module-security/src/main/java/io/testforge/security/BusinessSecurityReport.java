package io.testforge.security;

import java.util.List;

/** Coverage only: bodies, bindings, expected and observed values never appear here. */
public record BusinessSecurityReport(
        String suite, String version, boolean complete, int requests, List<Cell> cells) {
    public BusinessSecurityReport {
        cells = List.copyOf(cells);
    }

    public record Cell(
            String operation,
            String role,
            BusinessSecuritySuite.Kind kind,
            String status,
            String reason,
            List<String> cases) {
        public Cell {
            cases = List.copyOf(cases);
        }
    }
}
