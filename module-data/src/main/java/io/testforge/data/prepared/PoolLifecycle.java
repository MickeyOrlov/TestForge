package io.testforge.data.prepared;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.artifact.ArtifactSink;
import io.testforge.data.DataProperties;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.util.ClassUtils;

/**
 * Applies {@code forge.data.pool}: stocks the configured variants when the
 * context starts, runs background refill when any entry asks for it, and
 * publishes {@code pool-stats.json} (hits, cold misses, exhaustions) to the run
 * index when the context stops.
 *
 * <p>A failing preload fails the context: a suite that silently ran every test
 * on the slow path would hide exactly what the configuration was written for.
 *
 * <p>Default phase, so it stops before {@code module-reporting} writes its
 * manifest in the lowest phase.
 */
public class PoolLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PoolLifecycle.class);

    private final PreparedDataPool pool;
    private final DataProperties.PoolProperties properties;
    private final ArtifactSink artifactSink;
    private ExecutorService refillExecutor;
    private volatile boolean running;

    public PoolLifecycle(PreparedDataPool pool, DataProperties.PoolProperties properties, ArtifactSink artifactSink) {
        this.pool = pool;
        this.properties = properties;
        this.artifactSink = artifactSink == null ? ArtifactSink.NO_OP : artifactSink;
    }

    @Override
    public void start() {
        for (DataProperties.PreloadEntry entry : properties.preload()) {
            Class<?> type = resolve(entry.type());
            if (entry.refillBelow() > 0) {
                pool.refillWhenBelow(type, entry.tags(), entry.refillBelow(), entry.count());
            }
            try {
                pool.preload(type, entry.tags(), entry.count());
            } catch (RuntimeException e) {
                throw new IllegalStateException("Preloading %d x %s %s failed: %s"
                        .formatted(entry.count(), type.getSimpleName(), entry.tags(), e.getMessage()), e);
            }
            log.info("Prepared pool: stocked {} x {} {}", entry.count(), type.getSimpleName(), entry.tags());
        }
        if (properties.refillConfigured()) {
            refillExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "testforge-pool-refill");
                thread.setDaemon(true);
                return thread;
            });
            pool.enableRefill(refillExecutor);
        }
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        if (refillExecutor != null) {
            refillExecutor.shutdownNow();
        }
        publishStats();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void publishStats() {
        try {
            List<PreparedDataPool.VariantStats> stats = pool.stats();
            if (stats.isEmpty()) {
                return;
            }
            String json = new ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(Map.of("variants", stats));
            artifactSink.write("module-data", "pool-stats", "pool-stats.json", "application/json", json);
        } catch (Exception e) {
            // best-effort: statistics must never fail the run
            log.debug("Could not publish prepared pool statistics: {}", e.getMessage());
        }
    }

    private static Class<?> resolve(String type) {
        try {
            return ClassUtils.forName(type, PoolLifecycle.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            throw new IllegalStateException("forge.data.pool.preload type not found on the classpath: " + type, e);
        }
    }
}
