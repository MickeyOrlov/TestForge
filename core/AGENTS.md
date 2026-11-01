# core: working on this module

Read the root [AGENTS.md](../AGENTS.md) first. This file is about *changing*
`core`; how to *use* it lives in [README.md](README.md).

**Scope:** Shared primitives only: `ScenarioContext`/`ContextKey`, `Waiter`, `StateSnapshot`/`StateDiff`, `JsonPath`, the broker-neutral messaging types (`io.testforge.core.messaging`, used by every broker adapter), and the `ArtifactSink` seam (`io.testforge.artifact`).

**May depend on:** Nothing in TestForge. Every other module may depend on `core`.

## Invariants (do not break)

- A class belongs here only when two or more modules need it today. One user
  means it lives in that module.
- `ArtifactSink.NO_OP` must stay a safe default: producers call the sink
  unconditionally, so it must never throw or allocate per call.
- `ScenarioContext` is thread-local: one scenario, one thread. Do not add cross-
  thread sharing here.
- `Waiter` is the only way to wait. Never add an overload that sleeps for a
  fixed time.
- A `Waiter` timeout is a `WaitTimeoutException` extending Awaitility's
  `ConditionTimeoutException`; modules catch the latter, keep the hierarchy.
- Exceptions *and* assertion errors inside a wait condition mean "not ready
  yet" (as Awaitility's `ignoreExceptions()` did); `WaiterTest` pins it.

## Tests

Own: `ArtifactSinkTest`, `TestArtifactTest`, `WaiterTest`, `ScenarioContextTest`, `JsonPathTest`, `MessageProbeTest`. Example: `WaiterTimeoutExampleTest`. Examples: `ScenarioContextCleanupTest`, `StateDiffTest`.

## Before you finish

- `./gradlew :core:test :architecture-tests:test` is green, then `./gradlew build`.
- Public behaviour changed → update [README.md](README.md) and the example test
  in the same commit (`example-tests` is the living documentation).
- New dependency on another module → it must be listed in
  `architecture-tests/.../Modules.java` `ALLOWED_DEPENDENCIES`, and it must be
  a real need, not a convenience.
