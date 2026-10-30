# module-api-fuzz: working on this module

Read the root [AGENTS.md](../AGENTS.md) first. This file is about *changing*
`module-api-fuzz`; how to *use* it lives in [README.md](README.md).

**Scope:** Thin adapter over the Schemathesis CLI: config, safety policy, command, process, NDJSON ingestion, evidence.

**May depend on:** `module-api-discovery`.

## Invariants (do not break)

- TestForge contains no fuzz engine. Generation, shrinking, stateful testing
  belong to Schemathesis.
- `--include-method` alone is not enough; the generated `schemathesis.toml`
  enforces the method policy too. Change both together.
- Execution/config errors always fail `assertHealthy()`; findings fail only with
  `fail-on-findings=true`.
- URLs in evidence go through `UrlRedactor`.

## Tests

Own: 13 tests incl. `FuzzSafetyPolicyTest`, `SchemathesisCommandTest`, `NdjsonReportParserTest`; `RealSchemathesis*` need the CLI installed. Example: `ApiFuzzExampleTest`.

## Before you finish

- `./gradlew :module-api-fuzz:test :architecture-tests:test` is green, then `./gradlew build`.
- Public behaviour changed → update [README.md](README.md) and the example test
  in the same commit (`example-tests` is the living documentation).
- New dependency on another module → it must be listed in
  `architecture-tests/.../Modules.java` `ALLOWED_DEPENDENCIES`, and it must be
  a real need, not a convenience.
