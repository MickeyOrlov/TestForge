package io.testforge.reporting;

import org.springframework.context.SmartLifecycle;

public class ResourceUsageMonitorLifecycle implements SmartLifecycle {

    private final ResourceUsageMonitor monitor;
    private final ReportingProperties properties;

    public ResourceUsageMonitorLifecycle(ResourceUsageMonitor monitor, ReportingProperties properties) {
        this.monitor = monitor;
        this.properties = properties;
    }

    @Override
    public void start() {
        monitor.start(properties.resourceMonitor().period());
    }

    @Override
    public void stop() {
        monitor.stop();
    }

    @Override
    public boolean isRunning() {
        return monitor.isRunning();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }
}
