package io.testforge.reporting;

import io.qameta.allure.Allure;

/**
 * Attaches collected resource statistics to the current Allure test.
 *
 * <p>Optional dependency: compiled against
 * {@code io.qameta.allure:allure-java-commons}, which must be on the runtime
 * classpath of the test module that calls this. The monitor itself works
 * without Allure.
 */
public final class AllureResourceAttachments {

    private AllureResourceAttachments() {
    }

    public static void attach(ResourceUsageStats stats) {
        Allure.addAttachment("JVM resource usage", "text/plain", format(stats), ".txt");
    }

    private static String format(ResourceUsageStats stats) {
        return """
                samples:        %d
                heap used, MB:  min %d / avg %d / max %d
                process CPU:    avg %.2f / max %.2f
                system CPU:     avg %.2f / max %.2f
                """.formatted(
                stats.samples(),
                stats.memoryUsedMinMb(), stats.memoryUsedAvgMb(), stats.memoryUsedMaxMb(),
                stats.processCpuAvg(), stats.processCpuMax(),
                stats.systemCpuAvg(), stats.systemCpuMax());
    }
}
