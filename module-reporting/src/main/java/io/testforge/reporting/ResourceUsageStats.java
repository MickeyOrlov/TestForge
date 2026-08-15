package io.testforge.reporting;

public record ResourceUsageStats(
        int samples,
        long memoryUsedMinMb,
        long memoryUsedMaxMb,
        long memoryUsedAvgMb,
        double processCpuMax,
        double processCpuAvg,
        double systemCpuMax,
        double systemCpuAvg) {

    public String toFormattedText() {
        return """
                samples:        %d
                heap used, MB:  min %d / avg %d / max %d
                process CPU:    avg %.2f / max %.2f
                system CPU:     avg %.2f / max %.2f
                """.formatted(
                samples,
                memoryUsedMinMb, memoryUsedAvgMb, memoryUsedMaxMb,
                processCpuAvg, processCpuMax,
                systemCpuAvg, systemCpuMax);
    }
}
