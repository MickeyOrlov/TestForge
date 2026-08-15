package io.testforge.reporting;

import io.testforge.artifact.ArtifactSink;
import io.testforge.artifact.TestArtifact;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;

/**
 * Lifecycle component that automatically writes the artifact {@code manifest.json}
 * and {@code summary.md} at the end of a test run when the Spring ApplicationContext shuts down.
 *
 * <p><strong>CRITICAL CONTRACT:</strong> Reporting is best-effort and SECONDARY.
 * Writing must never throw an exception out of shutdown.
 */
public class ArtifactReportingLifecycle implements SmartLifecycle, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ArtifactReportingLifecycle.class);

    private final ArtifactSink sink;
    private final ArtifactRunLayout layout;
    private final ArtifactManifestWriter manifestWriter;
    private final ArtifactSummaryWriter summaryWriter;
    private volatile boolean running = false;
    private volatile boolean written = false;

    public ArtifactReportingLifecycle(
            ArtifactSink sink,
            ArtifactRunLayout layout,
            ArtifactManifestWriter manifestWriter,
            ArtifactSummaryWriter summaryWriter) {
        this.sink = sink;
        this.layout = layout;
        this.manifestWriter = manifestWriter;
        this.summaryWriter = summaryWriter;
    }

    @Override
    public void start() {
        this.running = true;
    }

    @Override
    public void stop() {
        if (!this.running) {
            return;
        }
        this.running = false;
        writeReports();
    }

    @Override
    public void destroy() {
        if (this.running) {
            stop();
        } else {
            writeReports();
        }
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    private synchronized void writeReports() {
        if (written) {
            return;
        }
        written = true;
        try {
            List<TestArtifact> artifacts = List.of();
            List<String> problems = List.of();

            if (sink instanceof RunArtifactSink runSink) {
                artifacts = runSink.artifacts();
                problems = runSink.problems().stream()
                        .map(p -> p.operation() + ": " + p.message())
                        .toList();
            }

            if (manifestWriter != null && layout != null) {
                manifestWriter.writeManifest(layout, artifacts, problems);
            }
            if (summaryWriter != null && layout != null) {
                summaryWriter.writeSummary(layout, artifacts, problems);
            }
        } catch (Throwable t) {
            log.warn("Failed to write artifact manifest or summary at shutdown: {}", t.getMessage(), t);
        }
    }
}
