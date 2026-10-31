# module-mock: working on this module

Read the root [AGENTS.md](../AGENTS.md) first. This file is about *changing*
`module-mock`; how to *use* it lives in [README.md](README.md).

**Scope:** Scenario-scoped stubs on a shared WireMock: `ScopedMockClient`, `MockScope`, `ScopeMarker` (body JSON path, header, query parameter or cookie), scoped journal assertions, mismatch diagnostics.

**May depend on:** `core` only.

Plan: [docs/mock-layer-roadmap.md](../docs/mock-layer-roadmap.md).

## Invariants (do not break)

- Default stubs priority 10+, scoped stubs priority 1 and ALWAYS with the scope
  matcher.
- Never register an unscoped catch-all from a test.
- Isolation comes from request matching; priority only settles ties.
- One `ScopeMarker` per scope drives stubs, journal and diagnostics; never let
  them match on different things.
- Diagnostics record the marker kind and name, never a header, query, cookie or
  body value.

## Tests

Own: `MockScopeTest`, `StubMismatchAnalyzerTest`, `ScopeMarkerTest` (every marker kind: isolation, journal, verify, diagnostics) and others. Examples: `ScopedMockIsolationTest`, `ApiClientExampleTest`, `HeaderScopedMockTest`.

## Before you finish

- `./gradlew :module-mock:test :architecture-tests:test` is green, then `./gradlew build`.
- Public behaviour changed → update [README.md](README.md) and the example test
  in the same commit (`example-tests` is the living documentation).
- New dependency on another module → it must be listed in
  `architecture-tests/.../Modules.java` `ALLOWED_DEPENDENCIES`, and it must be
  a real need, not a convenience.
