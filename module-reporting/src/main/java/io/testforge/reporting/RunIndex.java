package io.testforge.reporting;

import io.testforge.artifact.TestArtifact;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything every Spring context of this JVM has indexed per run root.
 *
 * <p>A suite caches several contexts; each one writes {@code manifest.json}
 * and {@code summary.md} when it closes. Written from its own artifacts only,
 * the last context to close replaced the index of all the others. Each one
 * now adds its artifacts here and writes the union, so the final index holds
 * the whole run.
 */
record RunIndex(List<TestArtifact> artifacts, List<String> problems) {

    private static final Map<Path, Accumulated> RUNS = new ConcurrentHashMap<>();

    private static final class Accumulated {
        final Map<String, TestArtifact> artifacts = new LinkedHashMap<>();
        final Set<String> problems = new LinkedHashSet<>();
    }

    static RunIndex add(Path runRoot, List<TestArtifact> artifacts, List<String> problems) {
        Accumulated run = RUNS.computeIfAbsent(runRoot.toAbsolutePath().normalize(), ignored -> new Accumulated());
        synchronized (run) {
            for (TestArtifact artifact : artifacts) {
                // keyed by file: the same file registered by two contexts is one entry
                run.artifacts.putIfAbsent(String.valueOf(artifact.file().toAbsolutePath().normalize()), artifact);
            }
            run.problems.addAll(problems);
            return new RunIndex(List.copyOf(new ArrayList<>(run.artifacts.values())), List.copyOf(run.problems));
        }
    }

    /** For tests: forget what earlier contexts indexed. */
    static void reset() {
        RUNS.clear();
    }
}
