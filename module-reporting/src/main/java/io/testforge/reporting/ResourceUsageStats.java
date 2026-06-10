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
}
