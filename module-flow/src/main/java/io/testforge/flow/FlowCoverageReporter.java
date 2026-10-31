package io.testforge.flow;

import io.testforge.artifact.ArtifactSink;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.context.SmartLifecycle;

/**
 * Publishes {@link FlowCoverage} as {@code module-flow/flow-coverage.md} in the
 * run index when the context stops. Report-only: an untaken branch is a gap
 * to read about, not a failed build, so {@code healthy} stays {@code true} and
 * the count of untaken transitions rides along as metadata.
 */
public class FlowCoverageReporter implements SmartLifecycle {

    private static final String SOURCE = "module-flow";

    private final FlowCoverage coverage;
    private final ArtifactSink sink;
    private volatile boolean running;

    public FlowCoverageReporter(FlowCoverage coverage, ArtifactSink sink) {
        this.coverage = coverage;
        this.sink = sink == null ? ArtifactSink.NO_OP : sink;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        if (coverage.isEmpty() || sink == ArtifactSink.NO_OP) {
            return;
        }
        try {
            long uncovered = coverage.entries().stream().mapToLong(entry -> entry.uncovered().size()).sum();
            Path file = sink.directoryFor(SOURCE).resolve("flow-coverage.md");
            Files.createDirectories(file.getParent());
            Files.writeString(file, coverage.render(), StandardCharsets.UTF_8);
            sink.registerFile(SOURCE, "flow-coverage", "flow-coverage.md", file, "text/markdown",
                    Map.of("healthy", "true", "uncovered", String.valueOf(uncovered)));
        } catch (Exception e) {
            // best-effort: a report that cannot be written never fails the run
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
