package io.testforge.web.prewarm;

import io.testforge.artifact.ArtifactSink;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Visits each configured page once per JVM and says what each one
 * answered. A prewarm failure is logged but never fails the suite — tests
 * against a cold environment are slower, not wrong — unless
 * {@code fail-when-unreachable} asks to stop when not one page answered.
 */
public class PrewarmRunner {

    private static final Logger log = LoggerFactory.getLogger(PrewarmRunner.class);
    /** Every page prewarmed in this JVM, so a second context warms only what the first did not. */
    private static final Set<String> VISITED = ConcurrentHashMap.newKeySet();
    private static final String SOURCE = "module-web";

    private final PrewarmProperties properties;
    private final PrewarmEngine engine;
    private final ArtifactSink sink;
    private final Set<String> visited;
    private volatile PrewarmReport lastReport;

    public PrewarmRunner(PrewarmProperties properties) {
        this(properties, new BrowserPrewarmEngine(), ArtifactSink.NO_OP);
    }

    public PrewarmRunner(PrewarmProperties properties, PrewarmEngine engine, ArtifactSink sink) {
        this(properties, engine, sink, VISITED);
    }

    /** {@code visited} is the once-per-JVM record of warmed pages; tests pass their own. */
    PrewarmRunner(PrewarmProperties properties, PrewarmEngine engine, ArtifactSink sink, Set<String> visited) {
        this.properties = properties;
        this.engine = engine;
        this.sink = sink == null ? ArtifactSink.NO_OP : sink;
        this.visited = visited;
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
        // each page once per JVM: a second context with the same pages skips, one with
        // other pages (another environment, another profile) still warms those
        List<String> pending = properties.urls().stream().distinct().filter(visited::add).toList();
        if (pending.isEmpty()) {
            log.debug("Every prewarm page was already visited in this JVM; skipping");
            return remember(PrewarmReport.skipped("already ran in this JVM"));
        }

        long started = System.currentTimeMillis();
        List<PrewarmVisit> visits = new ArrayList<>();
        try (PrewarmEngine.Session session = engine.open(properties)) {
            for (String url : pending) {
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
                String reason = BrowserPrewarmEngine.firstLine(scrubAll(e.getMessage(), pending));
                log.warn("Prewarm could not start, suite continues against a cold environment: {}", reason);
                return publish(PrewarmReport.skipped("could not start: " + reason));
            }
            log.warn("Prewarm stopped early: {}", BrowserPrewarmEngine.firstLine(scrubAll(e.getMessage(), pending)));
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

    private static String scrubAll(String message, List<String> urls) {
        String scrubbed = message;
        for (String url : urls) {
            scrubbed = UrlRedactor.scrub(scrubbed, url);
        }
        return scrubbed;
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
