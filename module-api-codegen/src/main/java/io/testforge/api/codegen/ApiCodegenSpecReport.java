package io.testforge.api.codegen;

import java.util.List;

public record ApiCodegenSpecReport(
        String specId,
        String location,
        boolean failed,
        int models,
        int clients,
        int operations,
        String sourceRoot,
        List<String> files,
        List<String> warnings,
        String error) {

    public ApiCodegenSpecReport {
        files = List.copyOf(files == null ? List.of() : files);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }
}
