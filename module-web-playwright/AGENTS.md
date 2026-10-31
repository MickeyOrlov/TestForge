# module-web-playwright: working on this module

Read the root [AGENTS.md](../AGENTS.md) first. This file is about *changing*
`module-web-playwright`; how to *use* it lives in [README.md](README.md).

**Scope:** Playwright lifecycle: one browser per JVM, fresh `BrowserContext` per test, `Page` fixture, failure artifacts.

**May depend on:** `core` only.

## Invariants (do not break)

- Browser-backed tests carry the `browser` tag and stay out of the default
  build.
- Each test gets an isolated context; never share a `Page` between tests.
- Artifacts are written only on failure and are best-effort: the original test
  failure always wins, an artifact error is attached as suppressed.

## Diagnostics

- `browser.md` holds redacted URLs, statuses, console and page-error first
  lines — never headers or bodies. Its listeners sit on the context, not the
  page, so popups count.

- The correlation header goes to the base URL's origin only, never to
  third-party origins.
- Failure artifacts cover lifecycle methods too, and are captured once per test.

## Tests

Own: `TestForgePlaywrightAutoConfigurationTest` (offline, never launches a browser). Example: `PlaywrightSmokeIT` (`./gradlew :example-tests:browsersTest`).

## Before you finish

- `./gradlew :module-web-playwright:test :architecture-tests:test` is green, then `./gradlew build`.
- Public behaviour changed → update [README.md](README.md) and the example test
  in the same commit (`example-tests` is the living documentation).
- New dependency on another module → it must be listed in
  `architecture-tests/.../Modules.java` `ALLOWED_DEPENDENCIES`, and it must be
  a real need, not a convenience.
