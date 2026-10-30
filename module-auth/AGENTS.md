# module-auth: working on this module

Read the root [AGENTS.md](../AGENTS.md) first. This file is about *changing*
`module-auth`; how to *use* it lives in [README.md](README.md).

**Scope:** Tokens per role: `TokenProvider` cache, token sources (static, OAuth2 client credentials/password, local HS256 JWT), `@AsUser`/`AuthContext` role selection, `AuthFilter` for REST Assured.

**May depend on:** `core` only. REST Assured is `compileOnly`; `AuthFilter` is created behind `@ConditionalOnClass`, so the module does not depend on `module-http` and stays deletable.

## Invariants (do not break)

- A token value never reaches a log, an exception message or `toString()`.
  Error messages name the role and endpoint, never the request body.
- No automatic retry on 401/403: a rejected fresh token is a finding.
- A header the test set explicitly is never replaced.
- Invalid role configuration fails at startup with every problem listed.
- Inert unless `forge.auth.enabled=true`; no token is fetched before a role is
  first used.
- No crypto or JWT library dependency; HS256 stays on `javax.crypto`.

## Tests

Own: `TokenProviderTest` (cache, refresh window, one fetch under parallel load), `LocalJwtSignerTest` (signature verified independently), `OAuth2TokenSourceTest` (JDK `HttpServer`, offline), `AuthFilterTest`, `AsUserExtensionTest` (incl. `@Nested`), `TestForgeAuthAutoConfigurationTest`. Example: `AuthExampleTest`.

## Before you finish

- `./gradlew :module-auth:test :architecture-tests:test` is green, then `./gradlew build`.
- Public behaviour changed → update [README.md](README.md) and the example test
  in the same commit (`example-tests` is the living documentation).
- New dependency on another module → it must be listed in
  `architecture-tests/.../Modules.java` `ALLOWED_DEPENDENCIES`, and it must be
  a real need, not a convenience.
