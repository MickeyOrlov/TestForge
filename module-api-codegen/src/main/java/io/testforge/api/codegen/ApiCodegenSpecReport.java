package io.testforge.api.codegen;

import java.util.List;

/**
 * @param compiled whether the generated sources compiled: {@code true},
 *        {@code false}, or {@code null} when the check was off or could not run
 * @param compileErrors {@code File.java:line: message}, at most 20 and a count
 *        of the rest
 */
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
        String error,
        Boolean compiled,
        List<String> compileErrors) {

    public ApiCodegenSpecReport {
        files = List.copyOf(files == null ? List.of() : files);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
        compileErrors = List.copyOf(compileErrors == null ? List.of() : compileErrors);
    }

    public ApiCodegenSpecReport(
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
        this(specId, location, failed, models, clients, operations, sourceRoot, files, warnings, error,
                null, List.of());
    }
}
