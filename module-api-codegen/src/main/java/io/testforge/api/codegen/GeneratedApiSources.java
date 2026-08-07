package io.testforge.api.codegen;

import java.util.List;

public record GeneratedApiSources(
        String packageName,
        int modelCount,
        int clientCount,
        int operationCount,
        List<GeneratedSource> sources,
        List<String> warnings) {

    public GeneratedApiSources {
        sources = List.copyOf(sources == null ? List.of() : sources);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }
}
