# module-data: working on this module

Read the root [AGENTS.md](../AGENTS.md) first. This file is about *changing*
`module-data`; how to *use* it lives in [README.md](README.md).

**Scope:** `RunUniqueValues`, `TemplateRenderer`, `Generators`, and the `@Prepared` pool (`PreparedDataProvider`, `PreparedDataPool`, `PoolLifecycle`: config preload, background refill, stats).

**May depend on:** `core` only.

## Invariants (do not break)

- Prepared objects are one-use only: no release/reuse semantics.
- A variant is type + tag set; never let tag order split stock again.
- Refill failures never reach a test; preload failures fail the context.
- Generated values are unique per run; the registry is per JVM and parallel-
  safe.
- Template rendering fails loudly on missing variables and cycles.

## Tests

Own: `RunUniqueValuesTest`, `GeneratorsTest`, `TemplateRendererTest`, `PreparedDataPoolTest` (incl. parallel exactly-once, tag sets, refill), `PoolLifecycleTest` (config preload, background refill, stats artifact). Examples: `DataHelpersTest`, `PreparedDataTest`, `PreparedPoolConfigExampleTest`, `ScopedRequestTemplateTest`.

## Before you finish

- `./gradlew :module-data:test :architecture-tests:test` is green, then `./gradlew build`.
- Public behaviour changed → update [README.md](README.md) and the example test
  in the same commit (`example-tests` is the living documentation).
- New dependency on another module → it must be listed in
  `architecture-tests/.../Modules.java` `ALLOWED_DEPENDENCIES`, and it must be
  a real need, not a convenience.
