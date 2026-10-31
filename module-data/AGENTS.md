# module-data: working on this module

Read the root [AGENTS.md](../AGENTS.md) first. This file is about *changing*
`module-data`; how to *use* it lives in [README.md](README.md).

**Scope:** `RunUniqueValues`, `TemplateRenderer`, `Generators`, `Factory`, `DataIsolation`, and the `@Prepared` pool (`PreparedDataProvider`, `PreparedDataPool`, `PoolLifecycle`: config preload, background refill, stats), and test-data cleanup (`Cleanup`, `CleanupExtension`, `Leftovers`, `LeftoverReporter`).

**May depend on:** `core` only.

## Invariants (do not break)

- Prepared objects are one-use only: no release/reuse semantics.
- A variant is type + tag set; never let tag order split stock again.
- Refill failures never reach a test; preload failures fail the context.
- Generated values are unique per run; the registry is per JVM and parallel-
  safe.
- Template rendering fails loudly on missing variables and cycles.

- Factories are immutable: every `with`/`without`/`as` returns a copy that
  shares the sequence and the uniqueness registry. Unknown fields and traits,
  missing record components and wrong component types fail loudly.
- Isolation tags match whole (`t1` never owns `t17`); the run tag is per JVM,
  the test tag per thread and closed after the test.
- `Factory.create` registers an undo only after persisting succeeded.
- Every undo that failed is recorded in `Leftovers` and published exactly
  once (`leftovers.json`, `healthy: false`); a clean run writes nothing and a
  reporting problem never fails the context stop.
- Cleanup never replaces a test's failure: attach as suppressed; only a
  passing test fails on leftovers. Registrations stay per thread.

Plan: [docs/data-layer-roadmap.md](../docs/data-layer-roadmap.md).

## Tests

Own: `RunUniqueValuesTest`, `FactoryTest`, `GeneratorsTest`, `TemplateRendererTest`, `PreparedDataPoolTest` (incl. parallel exactly-once, tag sets, refill), `PoolLifecycleTest` (config preload, background refill, stats artifact), `CleanupExtensionTest` (runs `CleanupTestkitFixture` through EngineTestKit; the fixture fails on purpose and is excluded from the normal test task), `DataIsolationExtensionTest` (runs `DataIsolationTestkitFixture` the same way), `LeftoverReporterTest`. Examples: `DataHelpersTest`, `FactoryExampleTest`, `DataIsolationExampleTest`, `LeftoverReportExampleTest`, `PreparedDataTest`, `PreparedPoolConfigExampleTest`, `CleanupExampleTest`, `ScopedRequestTemplateTest`.

## Before you finish

- `./gradlew :module-data:test :architecture-tests:test` is green, then `./gradlew build`.
- Public behaviour changed → update [README.md](README.md) and the example test
  in the same commit (`example-tests` is the living documentation).
- New dependency on another module → it must be listed in
  `architecture-tests/.../Modules.java` `ALLOWED_DEPENDENCIES`, and it must be
  a real need, not a convenience.
