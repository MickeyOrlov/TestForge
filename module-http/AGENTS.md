# module-http: working on this module

Read the root [AGENTS.md](../AGENTS.md) first. This file is about *changing*
`module-http`; how to *use* it lives in [README.md](README.md).

**Scope:** `ApiClient`: REST Assured specification per environment/service with scope and correlation propagation, redacted logging, opt-in retry, failed-test HTTP diagnostics (`HttpExchangeJournal`, `HttpDiagnosticsExtension`).

**May depend on:** `core` only.

## Invariants (do not break)

- Not an HTTP client: return plain REST Assured types, do not wrap them.
- Redaction runs before anything is logged or attached; new log paths must go
  through `Redactor`.
- Scope JSON injection works only for string/byte bodies; POJO bodies need the
  header mode.
- Retry is off by default and only for idempotent infrastructure noise.
- Authentication is a project `ApiRequestCustomizer`/`Filter`, not code here.

- Diagnostics never replace a test's failure: rethrow the original, attach
  problems as suppressed. The journal stays per thread and bounded, and only
  stores redacted text.

## Tests

Own: `RedactorTest`, `JsonScopeWriterTest`, `HttpPropertiesTest`, `TestForgeHttpAutoConfigurationTest`, `HttpDiagnosticsExtensionTest` (runs `HttpDiagnosticsTestkitFixture` through EngineTestKit; the fixture is excluded from the normal test task because it fails on purpose). Examples: `ApiClientExampleTest`, `ApiRetryExampleTest`, `HttpRedactionTest`.

## Before you finish

- `./gradlew :module-http:test :architecture-tests:test` is green, then `./gradlew build`.
- Public behaviour changed → update [README.md](README.md) and the example test
  in the same commit (`example-tests` is the living documentation).
- New dependency on another module → it must be listed in
  `architecture-tests/.../Modules.java` `ALLOWED_DEPENDENCIES`, and it must be
  a real need, not a convenience.
