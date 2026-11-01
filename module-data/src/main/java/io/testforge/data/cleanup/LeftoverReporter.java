package io.testforge.data.cleanup;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.artifact.ArtifactSink;
import io.testforge.data.isolation.DataIsolation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Publishes {@link Leftovers} to the run index as {@code module-data/leftovers.json}
 * when the context stops, with {@code healthy: false}, so {@code summary.md}
 * lists data the run left on the environment under <em>Needs attention</em>.
 * Nothing is written when cleanup undid everything.
 *
 * <p>Each leftover is published once: with several Spring contexts in one JVM
 * the first to stop takes them all. Default phase, so it stops before
 * {@code module-reporting} writes its manifest.
 */
public class LeftoverReporter implements SmartLifecycle {

    static final String SOURCE = "module-data";
    static final String FILE = "leftovers.json";

    private static final Logger log = LoggerFactory.getLogger(LeftoverReporter.class);

    private final ArtifactSink sink;
    private volatile boolean running;

    public LeftoverReporter(ArtifactSink sink) {
        this.sink = sink == null ? ArtifactSink.NO_OP : sink;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        List<Leftovers.Leftover> leftovers = Leftovers.drain();
        if (leftovers.isEmpty()) {
            return;
        }
        log.warn("{} cleanup action(s) left data behind on the environment; see {}/{}", leftovers.size(), SOURCE,
                FILE);
        try {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("runTag", DataIsolation.runTag());
            report.put("count", leftovers.size());
            report.put("leftovers", leftovers.stream().map(LeftoverReporter::entry).toList());
            Path file = sink.newFile(SOURCE, FILE);
            Files.createDirectories(file.getParent());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(file.toFile(), report);
            sink.registerFile(SOURCE, "leftovers", FILE, file, "application/json",
                    Map.of("healthy", "false", "leftovers", String.valueOf(leftovers.size())));
        } catch (Exception e) {
            // best-effort: a report that cannot be written never fails the run
            log.debug("Could not publish leftovers: {}", e.getMessage());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static Map<String, Object> entry(Leftovers.Leftover leftover) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("test", leftover.test());
        entry.put("description", leftover.description());
        entry.put("error", leftover.error());
        entry.put("scopeTag", leftover.scopeTag());
        entry.put("testFailed", leftover.testFailed());
        entry.put("at", leftover.at().toString());
        return entry;
    }
}
