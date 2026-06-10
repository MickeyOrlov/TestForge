package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.core.wait.Waiter;
import io.testforge.reporting.ResourceUsageMonitor;
import io.testforge.reporting.ResourceUsageStats;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Demonstrates module-reporting: collect lightweight resource metrics during a
 * run, then expose a compact summary for logs or CI artifacts.
 */
@SpringBootTest
class ResourceUsageMonitorTest {

    @Autowired
    ResourceUsageMonitor monitor;

    @Autowired
    Waiter waiter;

    @Test
    void samplesJvmResourceUsage() {
        monitor.reset();
        monitor.start(Duration.ofMillis(10));
        try {
            waiter.awaitTrue("resource monitor samples", () ->
                    monitor.stats().map(ResourceUsageStats::samples).orElse(0) >= 2);
        } finally {
            monitor.stop();
        }

        ResourceUsageStats stats = monitor.stats().orElseThrow();
        assertThat(stats.samples()).isGreaterThanOrEqualTo(2);
        assertThat(stats.memoryUsedMaxMb()).isGreaterThanOrEqualTo(stats.memoryUsedMinMb());
    }
}
