package io.testforge.load;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Execution seam for local k6; no HTTP or load generation lives in this interface. */
@FunctionalInterface
public interface LoadProcess {
    Result run(List<String> command, Path workingDirectory, Map<String, String> environment, Duration timeout)
            throws IOException, InterruptedException;

    /** Output is bounded by the default executor and is used only to recognize the k6 version. */
    record Result(int exitCode, boolean timedOut, String output) {
    }
}
