package io.testforge.api.fuzz;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Executes an external process. Implementations inherit the ambient environment
 * so credentials can be passed as environment variables and never as arguments.
 */
public interface ProcessRunner {
    ProcessResult run(List<String> command, Path workingDir, Map<String, String> extraEnv, Duration timeout);
}
