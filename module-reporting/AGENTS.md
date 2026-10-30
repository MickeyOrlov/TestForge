# module-reporting: working on this module

Read the root [AGENTS.md](../AGENTS.md) first. This file is about *changing*
`module-reporting`; how to *use* it lives in [README.md](README.md).

**Scope:** Run-scoped artifact directory, deterministic `manifest.json`/`summary.md`, resource monitor, optional Allure attachments.

**May depend on:** `core` only. Producers depend on the `ArtifactSink` seam in `core`, never on this module.

## Invariants (do not break)

- Best-effort: a reporting failure never fails a test.
- Relative paths only; no payload copying; producer metadata must be safe to
  publish.
- Output is deterministic (ordering, naming) and parallel-safe.
- Disabled by default.

## Tests

Own: 14 tests incl. `ReportingDeterminismTest`, `ReportingParallelSafetyTest`, `DiagnosticPathLeakTest`. Examples: `ArtifactReportingExampleTest`, `ResourceUsageMonitorTest`.

## Before you finish

- `./gradlew :module-reporting:test :architecture-tests:test` is green, then `./gradlew build`.
- Public behaviour changed → update [README.md](README.md) and the example test
  in the same commit (`example-tests` is the living documentation).
- New dependency on another module → it must be listed in
  `architecture-tests/.../Modules.java` `ALLOWED_DEPENDENCIES`, and it must be
  a real need, not a convenience.
