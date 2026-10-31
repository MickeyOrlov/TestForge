# module-db-contract: working on this module

Read the root [AGENTS.md](../AGENTS.md) first. This file is about *changing*
`module-db-contract`; how to *use* it lives in [README.md](README.md).

**Scope:** Schema snapshot, bounded diff, compatibility policy (`BREAKING`/`RISKY`/`NON_BREAKING`/`UNKNOWN`), CI gate. Reading via SchemaCrawler.

**May depend on:** `module-db`.

## Invariants (do not break)

- Fail closed: `assertCompatible()` fails when disabled or when there is no
  baseline.
- Generic JDBC retrieval on purpose; do not let SchemaCrawler pick a vendor
  plugin.
- Identifiers are stored as the vendor reports them; snapshots are comparable
  within one database lineage only.
- Snapshots carry a format version; never read one from another version
  silently.
- Baseline promotion is an explicit `writeBaseline()` call, never a side effect.
- `usedBy` and rename candidates inform the reader; they never change a
  verdict or the gate.

## Tests

Own: `DbSchemaComparatorTest`, `DefaultDbCompatibilityPolicyTest`, `SchemaCrawlerDbSchemaInspectorTest` and others. Examples: `DbContractExampleTest`, `PostgresDbContractIT` (tag `containers`).

## Before you finish

- `./gradlew :module-db-contract:test :architecture-tests:test` is green, then `./gradlew build`.
- Public behaviour changed → update [README.md](README.md) and the example test
  in the same commit (`example-tests` is the living documentation).
- New dependency on another module → it must be listed in
  `architecture-tests/.../Modules.java` `ALLOWED_DEPENDENCIES`, and it must be
  a real need, not a convenience.
