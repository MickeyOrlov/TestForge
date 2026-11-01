package io.testforge.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.artifact.ArtifactSink;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.SmartLifecycle;

/**
 * Publishes {@link FlowCoverage} as {@code module-flow/flow-coverage.md} in the
 * run index when the context stops, with {@code flow-coverage.json} beside it
 * for module-quality's flow-coverage dimension. Report-only: an untaken branch is a gap
 * to read about, not a failed build, so {@code healthy} stays {@code true} and
 * the count of untaken transitions rides along as metadata.
 */
public class FlowCoverageReporter implements SmartLifecycle {

    private static final String SOURCE = "module-flow";
    static final String JSON = "flow-coverage.json";

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
            Path json = file.resolveSibling(JSON);
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(json.toFile(), json());
            sink.registerFile(SOURCE, "flow-coverage", JSON, json, "application/json",
                    Map.of("uncovered", String.valueOf(uncovered)));
        } catch (Exception e) {
            // best-effort: a report that cannot be written never fails the run
        }
    }

    /** {@code flow-coverage.json}: the same entries, for module-quality. */
    Map<String, Object> json() {
        List<Map<String, Object>> flows = new ArrayList<>();
        for (FlowCoverage.Entry entry : coverage.entries()) {
            Map<String, Object> flow = new LinkedHashMap<>();
            flow.put("flow", entry.flow());
            flow.put("runs", entry.runs());
            flow.put("declared", entry.declared().size());
            flow.put("taken", List.copyOf(entry.taken()));
            flow.put("uncovered", List.copyOf(entry.uncovered()));
            flows.add(flow);
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("formatVersion", 1);
        json.put("flows", flows);
        return json;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
