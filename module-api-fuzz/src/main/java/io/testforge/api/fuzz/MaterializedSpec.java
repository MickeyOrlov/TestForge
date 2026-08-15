package io.testforge.api.fuzz;

import java.nio.file.Path;

/**
 * Represents a materialized OpenAPI document location for fuzzing.
 */
public sealed interface MaterializedSpec {

    record LocalFile(Path path) implements MaterializedSpec {
    }

    record RemoteUrl(String url) implements MaterializedSpec {
    }
}
