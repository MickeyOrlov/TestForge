# Running tests in parallel

The framework is designed for parallel execution; this guide lists what is
already safe, what to configure, and the few things that are not.

## Enable JUnit parallelism

`example-tests/src/test/resources/junit-platform.properties` (create it):

```properties
junit.jupiter.execution.parallel.enabled=true
junit.jupiter.execution.parallel.mode.default=same_thread
junit.jupiter.execution.parallel.mode.classes.default=concurrent
junit.jupiter.execution.parallel.config.strategy=fixed
junit.jupiter.execution.parallel.config.fixed.parallelism=4
```

Classes run concurrently, methods within a class stay on one thread — that
matches the framework's thread-per-scenario assumptions.

## What is parallel-safe out of the box

| Component | Why |
|---|---|
| `ScenarioContext` | thread-local; one scenario = one thread. Pair with `ScenarioContextExtension` so reused worker threads start clean |
| `MockScope` | isolation comes from the request-body matcher on the scope id, not from luck; use `mocks.scope()` for generated ids |
| `RunUniqueValues` | concurrent registry; generated values are unique across all threads of the run |
| `PreparedDataPool` | concurrent deques; every object is handed out exactly once |
| `KafkaMessageBuffer` | concurrent, bounded; search by unique keys/ids so scenarios don't match each other's messages |
| `DbWaiter` / `Waiter` | stateless polling around your own query |

## What to watch

- **Shared database state.** Assert on rows your scenario created (unique ids
  from `Generators`), never on table-wide counts. For side-effect checks under
  parallelism, `StateSnapshot.diff()` of a *filtered* query beats `findAll()`.
- **Scenarios that cannot be scoped.** A flow whose downstream requests carry
  nothing unique cannot use scoped mocks — tag such tests and run them
  serially (`@ResourceLock` or a dedicated serial suite), do not serialize the
  whole run.
- **Collector restarts.** `KafkaPollingCollector.stop()/start()` affects the
  whole JVM — keep such tests in a serial group.
- **`@TestMethodOrder` tests** (like `ScenarioContextCleanupTest`) rely on
  same-thread method execution — the config above preserves that.

## CI

Thread count belongs in one place: the JUnit properties file or
`-Djunit.jupiter.execution.parallel.config.fixed.parallelism=N` from the CI
job — not scattered across Gradle forks. Keep `maxParallelForks` at 1 and let
JUnit parallelism do the work inside one JVM, so Spring contexts are shared
instead of duplicated per fork.
