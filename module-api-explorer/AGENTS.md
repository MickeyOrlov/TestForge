# module-api-explorer: working on this module

Read the root [AGENTS.md](../AGENTS.md) first. This file is about *changing*
`module-api-explorer`; how to *use* it lives in [README.md](README.md).

**Scope:** Runs an OpenAPI document against a live environment and reports runtime contract drift per operation. Stateful only through explicit `extract` rules; `RuleProposer` suggests rules from the document (all four steps of [docs/api-explorer-stateful-design.md](../docs/api-explorer-stateful-design.md)).

**May depend on:** `module-api-discovery`, `module-http`.

## Invariants (do not break)

- Inert unless `forge.api-explorer.enabled=true`.
- GET/HEAD/OPTIONS by default; any write method needs both the method listed and
  `allow-unsafe-methods=true`. Never weaken `SafetyPolicy` to make a test
  pass.
- No synthesized request bodies: only `forge.api-explorer.bodies` templates.
  Templates and rules never bypass the two write keys.
- `DELETE` runs after every other operation; keep it that way when touching
  `OperationOrder`.
- A run that sent write requests says so, with the base URL, in the report.
- Values flow between calls only through an explicit rule; inference may
  propose rules in the report, never apply them.
- Extracted values never appear in the report or summary — only in the
  consuming observation's bindings. One `ExtractedValues` per spec run.
- Observations are redacted through `forge.http.logging.redact-*`.
- `ExchangeExecutor` is the seam between live and replayed runs
  (`ApiClientExchangeExecutor`, `ReplayExchangeExecutor`); keep the runner
  independent of REST Assured details.
- Replay never fakes what it does not have: unrecorded, failed or truncated
  recordings come back as failed exchanges with the reason.

## Tests

Own: `SafetyPolicyTest`, `RequestPlanningTest`, `ResponseContractCheckerTest`, `ObservationFactoryTest`, `ApiExplorerRunnerTest`, `StatefulExplorationTest`, `RuleProposerTest`, `WriteChainTest`, `ReplayTest`. Examples: `ApiExplorerExampleTest`, `ApiExplorerStatefulExampleTest`, `ApiExplorerProposalExampleTest`, `ApiExplorerWriteChainExampleTest`, `ApiExplorerLiveIT` (tag `live-api`).

## Before you finish

- `./gradlew :module-api-explorer:test :architecture-tests:test` is green, then `./gradlew build`.
- Public behaviour changed → update [README.md](README.md) and the example test
  in the same commit (`example-tests` is the living documentation).
- New dependency on another module → it must be listed in
  `architecture-tests/.../Modules.java` `ALLOWED_DEPENDENCIES`, and it must be
  a real need, not a convenience.
