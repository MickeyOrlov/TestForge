package io.testforge.api.explorer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.artifact.ArtifactSink;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.SmartLifecycle;

/**
 * Publishes what the guard found as {@code contract-guard.json} in the run
 * index when the context stops, with a {@code healthy} verdict, so drift shows
 * up under "Needs attention" in {@code summary.md}. Default phase: stops before
 * module-reporting writes its manifest.
 */
public class ContractGuardReporter implements SmartLifecycle {

    private static final String SOURCE = "module-api-explorer";

    private final ContractGuard guard;
    private final ArtifactSink sink;
    private volatile boolean running;

    public ContractGuardReporter(ContractGuard guard, ArtifactSink sink) {
        this.guard = guard;
        this.sink = sink == null ? ArtifactSink.NO_OP : sink;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        if (guard.checkedResponses() == 0) {
            return;
        }
        try {
            List<ContractGuard.Finding> findings = guard.findings();
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("checkedResponses", guard.checkedResponses());
            report.put("distinctFindings", findings.size());
            report.put("findings", findings);
            Path file = sink.directoryFor(SOURCE).resolve("contract-guard.json");
            Files.createDirectories(file.getParent());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(file.toFile(), report);
            sink.registerFile(SOURCE, "contract-guard", "contract-guard.json", file, "application/json",
                    Map.of("healthy", String.valueOf(findings.isEmpty()),
                            "checked", String.valueOf(guard.checkedResponses())));
        } catch (Exception e) {
            // best-effort: a report that cannot be written never fails the run
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
