# module-contract-monitor: working on this module

Read the root [AGENTS.md](../AGENTS.md) first. This file is about *changing*
`module-contract-monitor`; how to *use* it lives in [README.md](README.md).

**Scope:** Scheduled Kafka drift check: `ContractMonitorCase` (filter + contract), shape snapshots, baseline diff, report.

**May depend on:** `module-kafka`, `module-contract`.

Plan: [docs/monitor-layer-roadmap.md](../docs/monitor-layer-roadmap.md).

## Invariants (do not break)

- Shape snapshots contain types only, never payload values.
- Never rewrite baselines under `src/test/resources`; promotion is an explicit,
  reviewed act.
- Artifacts are redacted before they are written.

## Tests

Own: `ContractMonitorRunnerTest`, `ShapeDiffTest`, `PayloadShapeNormalizerTest`. Example: `ContractMonitorExampleTest`.

## Before you finish

- `./gradlew :module-contract-monitor:test :architecture-tests:test` is green, then `./gradlew build`.
- Public behaviour changed → update [README.md](README.md) and the example test
  in the same commit (`example-tests` is the living documentation).
- New dependency on another module → it must be listed in
  `architecture-tests/.../Modules.java` `ALLOWED_DEPENDENCIES`, and it must be
  a real need, not a convenience.
