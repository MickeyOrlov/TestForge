package io.testforge.api.codegen;

import java.util.List;

/**
 * @param surface what tests compile against in these sources, for comparing
 *        one generation with the next
 */
public record GeneratedApiSources(
        String packageName,
        int modelCount,
        int clientCount,
        int operationCount,
        List<GeneratedSource> sources,
        List<String> warnings,
        GeneratedSurface surface) {

    public GeneratedApiSources {
        sources = List.copyOf(sources == null ? List.of() : sources);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
        surface = surface == null ? new GeneratedSurface(null) : surface;
    }

    public GeneratedApiSources(
            String packageName,
            int modelCount,
            int clientCount,
            int operationCount,
            List<GeneratedSource> sources,
            List<String> warnings) {
        this(packageName, modelCount, clientCount, operationCount, sources, warnings, null);
    }
}
