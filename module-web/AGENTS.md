# module-web: working on this module

Read the root [AGENTS.md](../AGENTS.md) first. This file is about *changing*
`module-web`; how to *use* it lives in [README.md](README.md).

**Scope:** `PrewarmRunner`: visit 2–4 heavy pages once per JVM before tests.

**May depend on:** `core` only.

## Invariants (do not break)

- Best-effort: a prewarm failure is logged, never fails the suite.
- Exactly once per JVM.
- Inert unless `forge.prewarm.enabled=true`; no browser starts in the default
  build.
- Browser testing belongs in `module-web-playwright`, not here.

## Tests

Own: `TestForgeWebAutoConfigurationTest` (offline, no browser).

## Before you finish

- `./gradlew :module-web:test :architecture-tests:test` is green, then `./gradlew build`.
- Public behaviour changed → update [README.md](README.md) and the example test
  in the same commit (`example-tests` is the living documentation).
- New dependency on another module → it must be listed in
  `architecture-tests/.../Modules.java` `ALLOWED_DEPENDENCIES`, and it must be
  a real need, not a convenience.
