# module-web: working on this module

Read the root [AGENTS.md](../AGENTS.md) first. This file is about *changing*
`module-web`; how to *use* it lives in [README.md](README.md).

**Scope:** `PrewarmRunner`: visit 2–4 heavy pages once per JVM before tests.

**May depend on:** `core` only.

## Invariants (do not break)

- Best-effort: a prewarm failure is logged and never fails the suite, unless
  `fail-when-unreachable=true` and not one page answered. A browser that
  cannot start never counts as an unreachable environment.
- URLs go through `UrlRedactor` before they are logged or reported.
- Exactly once per JVM.
- Inert unless `forge.prewarm.enabled=true`; no browser starts in the default
  build.
- Browser testing belongs in `module-web-playwright`, not here.

## Tests

Own: `TestForgeWebAutoConfigurationTest`, `PrewarmRunnerTest` (fake engine), `HttpPrewarmEngineTest` (local HTTP server) — all offline, no browser. Example: `PrewarmExampleTest` (HTTP mode against WireMock).

## Before you finish

- `./gradlew :module-web:test :architecture-tests:test` is green, then `./gradlew build`.
- Public behaviour changed → update [README.md](README.md) and the example test
  in the same commit (`example-tests` is the living documentation).
- New dependency on another module → it must be listed in
  `architecture-tests/.../Modules.java` `ALLOWED_DEPENDENCIES`, and it must be
  a real need, not a convenience.
