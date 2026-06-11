# module-reporting

Diagnostics helpers for test runs.

## What's inside

- **`ResourceUsageMonitor`** — lightweight JVM memory and CPU sampler.
- **`ResourceUsageStats`** — min/max/average summary that CI reports can attach
  or print after a run.
- **`AllureResourceAttachments.attach(stats)`** — one-liner to attach the
  summary to the current Allure test. Optional: compiled against
  `allure-java-commons` (`compileOnly`), add it to your test module's runtime
  classpath to use this.

The module has no hard dependency on Allure — without it, write
`ResourceUsageStats` into logs, metrics, or CI artifacts.

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
