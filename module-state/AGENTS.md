# module-state: working on this module

Read the root [AGENTS.md](../AGENTS.md) first. This file is about *changing*
`module-state`; how to *use* it lives in [README.md](README.md).

**Scope:** `StateRecipe` over `module-flow`, bridged into `@Prepared` via `StatePreparedDataProvider`.

**May depend on:** `module-flow`, `module-data`.

## Invariants (do not break)

- Recipes drive product/test-support APIs; direct DB writes are a project
  decision, not a default here.
- Tags select states (`state:approved`); keep tag parsing backward compatible.

## Tests

Own: `StateRequestTest`, `StateRecipeExecutorTest`. Example: `StateRecipePreparedDataTest`.

## Before you finish

- `./gradlew :module-state:test :architecture-tests:test` is green, then `./gradlew build`.
- Public behaviour changed → update [README.md](README.md) and the example test
  in the same commit (`example-tests` is the living documentation).
- New dependency on another module → it must be listed in
  `architecture-tests/.../Modules.java` `ALLOWED_DEPENDENCIES`, and it must be
  a real need, not a convenience.
