# module-db: working on this module

Read the root [AGENTS.md](../AGENTS.md) first. This file is about *changing*
`module-db`; how to *use* it lives in [README.md](README.md).

**Scope:** Gray-box DB checks: `DbWaiter`, `RepositoryPollingAspect`, SQL logging, `SchemaValidator` (entity ↔ table drift), `DataSourceRegistry`.

**May depend on:** `core` only.

## Invariants (do not break)

- `SqlLoggingDataSourcePostProcessor` wraps the DataSource; never cast the bean
  to a pool type.
- `SchemaValidator` resolves names reflectively and does not understand
  inheritance or custom naming strategies; extend it explicitly before
  claiming support.
- Nullability drift is one-directional by design.
- Repository polling is opt-in (`forge.db.repository-polling.enabled`).

## Tests

Own: 8 tests incl. `SchemaDriftTest`, `MultiDataSourceIsolationTest`. Examples: `DbWaiterTest`, `MultiDataSourceExampleTest`, `SchemaValidatorTest`, `PostgresSchemaValidationIT` (tag `containers`).

## Before you finish

- `./gradlew :module-db:test :architecture-tests:test` is green, then `./gradlew build`.
- Public behaviour changed → update [README.md](README.md) and the example test
  in the same commit (`example-tests` is the living documentation).
- New dependency on another module → it must be listed in
  `architecture-tests/.../Modules.java` `ALLOWED_DEPENDENCIES`, and it must be
  a real need, not a convenience.
