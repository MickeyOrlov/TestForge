package io.testforge.web.prewarm;

import io.testforge.artifact.ArtifactSink;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Visits the configured pages exactly once per JVM and says what each one
 * answered. A prewarm failure is logged but never fails the suite — tests
 * against a cold environment are slower, not wrong — unless
 * {@code fail-when-unreachable} asks to stop when not one page answered.
 */
public class PrewarmRunner {

    private static final Logger log = LoggerFactory.getLogger(PrewarmRunner.class);
    private static final AtomicBoolean DONE = new AtomicBoolean(false);
    private static final String SOURCE = "module-web";

    private final PrewarmProperties properties;
    private final PrewarmEngine engine;
    private final ArtifactSink sink;
    private final AtomicBoolean done;
    private volatile PrewarmReport lastReport;

    public PrewarmRunner(PrewarmProperties properties) {
        this(properties, new BrowserPrewarmEngine(), ArtifactSink.NO_OP);
    }

    public PrewarmRunner(PrewarmProperties properties, PrewarmEngine engine, ArtifactSink sink) {
        this(properties, engine, sink, DONE);
    }

    /** {@code done} is the once-per-JVM guard; tests pass their own. */
    PrewarmRunner(PrewarmProperties properties, PrewarmEngine engine, ArtifactSink sink, AtomicBoolean done) {
        this.properties = properties;
        this.engine = engine;
        this.sink = sink == null ? ArtifactSink.NO_OP : sink;
        this.done = done;
    }

    /** What the last {@link #runOnce()} of this runner found; {@code null} before it ran. */
    public PrewarmReport lastReport() {
        return lastReport;
    }

    public PrewarmReport runOnce() {
        if (!properties.enabled()) {
            return remember(PrewarmReport.skipped("disabled"));
        }
        if (properties.urls().isEmpty()) {
            return remember(PrewarmReport.skipped("no URLs configured"));
        }
        if (!done.compareAndSet(false, true)) {
            log.debug("Prewarm already ran in this JVM; skipping");
            return remember(PrewarmReport.skipped("already ran in this JVM"));
        }

        long started = System.currentTimeMillis();
        List<PrewarmVisit> visits = new ArrayList<>();
        try (PrewarmEngine.Session session = engine.open(properties)) {
            for (String url : properties.urls()) {
                PrewarmVisit visit = session.visit(url);
                visits.add(visit);
                if (visit.reached()) {
                    log.info("Prewarm: {}", visit.describe());
                } else {
                    log.warn("Prewarm: {}", visit.describe());
                }
            }
        } catch (RuntimeException e) {
            if (visits.isEmpty()) {
                // could not start a browser: says nothing about the environment
                log.warn("Prewarm could not start, suite continues against a cold environment: {}",
                        BrowserPrewarmEngine.firstLine(e.getMessage()));
                return publish(PrewarmReport.skipped("could not start: "
                        + BrowserPrewarmEngine.firstLine(e.getMessage())));
            }
            log.warn("Prewarm stopped early: {}", BrowserPrewarmEngine.firstLine(e.getMessage()));
        }
        PrewarmReport report = new PrewarmReport(visits, System.currentTimeMillis() - started, null);
        log.info("Prewarm finished: {} of {} page(s) answered in {} ms",
                visits.stream().filter(PrewarmVisit::reached).count(), visits.size(), report.millis());
        publish(report);
        if (!report.environmentReached() && properties.failWhenUnreachable()) {
            throw new PrewarmException(report);
        }
        return report;
    }

    private PrewarmReport publish(PrewarmReport report) {
        try {
            sink.write(SOURCE, "prewarm", "prewarm.md", "text/markdown", report.markdown());
        } catch (RuntimeException e) {
            // a diagnostic never fails the producer
        }
        return remember(report);
    }

    private PrewarmReport remember(PrewarmReport report) {
        lastReport = report;
        return report;
    }
}
