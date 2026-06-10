# module-reporting

Diagnostics helpers for test runs.

## What's inside

- **`ResourceUsageMonitor`** — lightweight JVM memory and CPU sampler.
- **`ResourceUsageStats`** — min/max/average summary that CI reports can attach
  or print after a run.

The module intentionally has no hard dependency on Allure. Adapted projects can
write `ResourceUsageStats` into Allure, logs, metrics, or CI artifacts.

## Configuration

```yaml
forge:
  reporting:
    resource-monitor:
      enabled: true
      period: 2s
```

## Usage

```java
monitor.start(Duration.ofSeconds(1));
// run test work
ResourceUsageStats stats = monitor.stop().orElseThrow();
```
