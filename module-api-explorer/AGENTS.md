# module-api-explorer: working on this module

Read the root [AGENTS.md](../AGENTS.md) first. This file is about *changing*
`module-api-explorer`; how to *use* it lives in [README.md](README.md).

**Scope:** Runs an OpenAPI document against a live environment and reports runtime contract drift per operation. Stateless in v1.

**May depend on:** `module-api-discovery`, `module-http`.

## Invariants (do not break)

- Inert unless `forge.api-explorer.enabled=true`.
- GET/HEAD/OPTIONS by default; any write method needs both the method listed and
  `allow-unsafe-methods=true`. Never weaken `SafetyPolicy` to make a test
  pass.
- No synthesized request bodies until value extraction exists (see BACKLOG).
- Observations are redacted through `forge.http.logging.redact-*`.
- `ExchangeExecutor` is the seam for replay; keep the runner independent of REST
  Assured details.

## Tests

Own: `SafetyPolicyTest`, `RequestPlanningTest`, `ResponseContractCheckerTest`, `ObservationFactoryTest`, `ApiExplorerRunnerTest`. Examples: `ApiExplorerExampleTest`, `ApiExplorerLiveIT` (tag `live-api`).

## Before you finish

- `./gradlew :module-api-explorer:test :architecture-tests:test` is green, then `./gradlew build`.
- Public behaviour changed → update [README.md](README.md) and the example test
  in the same commit (`example-tests` is the living documentation).
- New dependency on another module → it must be listed in
  `architecture-tests/.../Modules.java` `ALLOWED_DEPENDENCIES`, and it must be
  a real need, not a convenience.
