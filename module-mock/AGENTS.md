# module-mock: working on this module

Read the root [AGENTS.md](../AGENTS.md) first. This file is about *changing*
`module-mock`; how to *use* it lives in [README.md](README.md).

**Scope:** Scenario-scoped stubs on a shared WireMock: `ScopedMockClient`, `MockScope`, mismatch diagnostics.

**May depend on:** `core` only.

## Invariants (do not break)

- Default stubs priority 10+, scoped stubs priority 1 and ALWAYS with the scope
  matcher.
- Never register an unscoped catch-all from a test.
- Isolation comes from request matching; priority only settles ties.

## Tests

Own: 7 tests incl. `MockScopeTest`, `StubMismatchAnalyzerTest`. Examples: `ScopedMockIsolationTest`, `ApiClientExampleTest`.

## Before you finish

- `./gradlew :module-mock:test :architecture-tests:test` is green, then `./gradlew build`.
- Public behaviour changed → update [README.md](README.md) and the example test
  in the same commit (`example-tests` is the living documentation).
- New dependency on another module → it must be listed in
  `architecture-tests/.../Modules.java` `ALLOWED_DEPENDENCIES`, and it must be
  a real need, not a convenience.
