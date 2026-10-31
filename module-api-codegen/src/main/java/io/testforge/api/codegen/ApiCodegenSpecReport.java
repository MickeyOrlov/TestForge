package io.testforge.api.codegen;

import io.testforge.api.discovery.ApiChange;
import java.util.List;

/**
 * @param compiled whether the generated sources compiled: {@code true},
 *        {@code false}, or {@code null} when the check was off or could not run
 * @param compileErrors {@code File.java:line: message}, at most 20 and a count
 *        of the rest
 * @param comparedWith the {@code surface.json} this generation was compared
 *        with, or {@code null} when there was none (a first generation)
 * @param changes what this generation changed for tests compiled against
 *        {@code comparedWith}, worst first
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
        List<String> compileErrors,
        String comparedWith,
        List<ApiChange> changes) {

    public ApiCodegenSpecReport {
        files = List.copyOf(files == null ? List.of() : files);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
        compileErrors = List.copyOf(compileErrors == null ? List.of() : compileErrors);
        changes = List.copyOf(changes == null ? List.of() : changes);
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
                null, List.of(), null, List.of());
    }

    /** The changes that stop a test compiled against the baseline from compiling. */
    public List<ApiChange> breakingChanges() {
        return changes.stream().filter(change -> change.impact() == ApiChange.Impact.BREAKING).toList();
    }
}
