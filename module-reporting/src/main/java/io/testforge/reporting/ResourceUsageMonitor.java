package io.testforge.reporting;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Samples JVM heap and CPU usage on a background thread for run diagnostics.
 *
 * <p>Relies on {@code com.sun.management.OperatingSystemMXBean} — available on
 * all HotSpot-family JVMs (OpenJDK, Temurin, Corretto, Oracle), but not part
 * of the Java SE spec; exotic runtimes may need a different CPU source.
 */
public class ResourceUsageMonitor {

    private static final Logger log = LoggerFactory.getLogger(ResourceUsageMonitor.class);

    private final Object lock = new Object();
    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final OperatingSystemMXBean os =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    private final AtomicInteger samples = new AtomicInteger();
    private final AtomicLong memoryMinMb = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong memoryMaxMb = new AtomicLong();
    private final AtomicLong memorySumMb = new AtomicLong();
    private final AtomicLong processCpuMax = new AtomicLong();
    private final AtomicLong processCpuSum = new AtomicLong();
    private final AtomicInteger processCpuSamples = new AtomicInteger();
    private final AtomicLong systemCpuMax = new AtomicLong();
    private final AtomicLong systemCpuSum = new AtomicLong();
    private final AtomicInteger systemCpuSamples = new AtomicInteger();

    private ScheduledExecutorService scheduler;

    public void start(Duration period) {
        if (period == null || period.isZero() || period.isNegative()) {
            period = Duration.ofSeconds(2);
        }

        synchronized (lock) {
            if (scheduler != null) {
                return;
            }
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "testforge-resource-monitor");
                thread.setDaemon(true);
                return thread;
            });
            scheduler.scheduleWithFixedDelay(this::sampleSafely, 0, period.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    public Optional<ResourceUsageStats> stop() {
        ScheduledExecutorService toStop;
        synchronized (lock) {
            toStop = scheduler;
            scheduler = null;
        }
        if (toStop != null) {
            toStop.shutdownNow();
        }
        return stats();
    }

    public boolean isRunning() {
        synchronized (lock) {
            return scheduler != null;
        }
    }

    public void reset() {
        samples.set(0);
        memoryMinMb.set(Long.MAX_VALUE);
        memoryMaxMb.set(0);
        memorySumMb.set(0);
        processCpuMax.set(0);
        processCpuSum.set(0);
        processCpuSamples.set(0);
        systemCpuMax.set(0);
        systemCpuSum.set(0);
        systemCpuSamples.set(0);
    }

    public Optional<ResourceUsageStats> stats() {
        int sampleCount = samples.get();
        if (sampleCount == 0) {
            return Optional.empty();
        }

        return Optional.of(new ResourceUsageStats(
                sampleCount,
                minOrZero(memoryMinMb),
                memoryMaxMb.get(),
                memorySumMb.get() / sampleCount,
                scaled(processCpuMax.get()),
                avgScaled(processCpuSum, processCpuSamples),
                scaled(systemCpuMax.get()),
                avgScaled(systemCpuSum, systemCpuSamples)));
    }

    private void sampleSafely() {
        try {
            sample();
        } catch (RuntimeException e) {
            log.warn("Resource monitor sample failed", e);
        }
    }

    private void sample() {
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();
        long usedMb = mb(heap.getUsed()) + mb(nonHeap.getUsed());
        update(memoryMinMb, memoryMaxMb, memorySumMb, usedMb);

        updateCpu(os.getProcessCpuLoad(), processCpuMax, processCpuSum, processCpuSamples);
        updateCpu(os.getSystemCpuLoad(), systemCpuMax, systemCpuSum, systemCpuSamples);
        samples.incrementAndGet();
    }

    private void updateCpu(double value, AtomicLong max, AtomicLong sum, AtomicInteger count) {
        if (value < 0) {
            return;
        }
        long scaled = Math.round(value * 10_000);
        updateMax(max, scaled);
        sum.addAndGet(scaled);
        count.incrementAndGet();
    }

    private void update(AtomicLong min, AtomicLong max, AtomicLong sum, long value) {
        min.accumulateAndGet(value, Math::min);
        updateMax(max, value);
        sum.addAndGet(value);
    }

    private void updateMax(AtomicLong target, long value) {
        target.accumulateAndGet(value, Math::max);
    }

    private long minOrZero(AtomicLong value) {
        long current = value.get();
        return current == Long.MAX_VALUE ? 0 : current;
    }

    private double avgScaled(AtomicLong sum, AtomicInteger count) {
        int samplesWithValue = count.get();
        return samplesWithValue == 0 ? 0.0 : scaled(sum.get() / samplesWithValue);
    }

    private double scaled(long value) {
        return value / 10_000.0;
    }

    private long mb(long bytes) {
        return bytes / (1024 * 1024);
    }
}
