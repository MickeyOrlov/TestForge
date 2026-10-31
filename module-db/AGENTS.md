# module-db: working on this module

Read the root [AGENTS.md](../AGENTS.md) first. This file is about *changing*
`module-db`; how to *use* it lives in [README.md](README.md).

**Scope:** Gray-box DB checks: `DbWaiter`, `RepositoryPollingAspect`, SQL logging, `SchemaValidator` (entity ↔ table drift), `DataSourceRegistry`, `DbState` table snapshots and diffs, `TableWriter` setup rows with registered deletes, `SqlJournal` + `DbDiagnosticsExtension` (SQL of failed tests). Plan: [docs/db-layer-roadmap.md](../docs/db-layer-roadmap.md).

**May depend on:** `core`, `module-data` (only `Cleanup`, for `TableWriter`'s deletes).

## Invariants (do not break)

- `SqlLoggingDataSourcePostProcessor` wraps the DataSource; never cast the bean
  to a pool type.
- `SchemaValidator` stays reflective and ORM-free: no Hibernate types, no
  metamodel. Inheritance follows the root's `@Inheritance`; implicit names go
  through `SchemaNamingStrategy`, explicit names never do.
- Every new mapping rule needs an H2 test that proves both a clean schema and
  a drifted one (see `SchemaInheritanceTest`, `SchemaIndexForeignKeyDriftTest`),
  and a PostgreSQL case in `PostgresSchemaValidationIT` when metadata differs
  by vendor.
- Indexes match by columns, never names; foreign keys only when declared
  unless `withImplicitForeignKeys`. Drift is one-directional: never report what
  the database has extra.
- Nullability drift is one-directional by design.
- Repository polling is opt-in (`forge.db.repository-polling.enabled`).
- SQL diagnostics are opt-in (`forge.db.diagnostics.enabled`) because they wrap the DataSource; logging and recording share one proxy.
- `DbState` stays plain JDBC: table and column names are validated identifiers, never concatenated user input; snapshots are bounded (`maxRows`).

- Deeper checks (`row(...)`, `watch`/`watchSchema`, `invariants()`,
  `onlyOwnData`) only read; they never change a row. Discovered table names
  are quoted, user-given ones are validated as plain identifiers.

## Tests

Own: `SchemaDriftTest`, `SchemaInheritanceTest`, `SchemaIndexForeignKeyDriftTest`, `MultiDataSourceIsolationTest` and others. Examples: `DbWaiterTest`, `MultiDataSourceExampleTest`, `SchemaValidatorTest`, `PostgresSchemaValidationIT` (tag `containers`).

## Before you finish

- `./gradlew :module-db:test :architecture-tests:test` is green, then `./gradlew build`.
- Public behaviour changed → update [README.md](README.md) and the example test
  in the same commit (`example-tests` is the living documentation).
- New dependency on another module → it must be listed in
  `architecture-tests/.../Modules.java` `ALLOWED_DEPENDENCIES`, and it must be
  a real need, not a convenience.
