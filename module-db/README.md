# module-db

Database-level assertions for gray-box testing: verify not only what the API
answered, but what the services actually persisted.

## What's inside

- **`DbWaiter`** — polls a repository call until the row written by an
  asynchronous backend process appears. The antidote to `Thread.sleep`.
  Use `on(name)` to target a named datasource.
- **`RepositoryPollingAspect`** — optional naming-convention wrapper:
  `waitBy...` default repository methods poll the matching `findBy...` query.
- **`SqlLoggingDataSourcePostProcessor`** — logs every SQL statement tests
  execute (logger `forge.sql`), enabled by `forge.db.log-sql: true`.
- **`SchemaValidator`** — compares an entity's mapped columns against the real
  database schema to detect drift: missing columns, column type family drift,
  and nullability drift. Catches service migrations that silently break the
  test framework's mappings. Run one test per entity in a scheduled CI job.
  Use `forDataSource(name)` to validate against a non-default database.

## Configuration

```yaml
forge:
  db:
    log-sql: true   # default: false
    default-datasource: primaryDataSource  # optional; bean name
    repository-polling:
      enabled: true # default: false
```

## Usage

```java
TaskRecord row = dbWaiter.awaitRow(
        "task_record for task " + taskId,
        () -> taskRepository.findByTaskId(taskId));

assertThat(schemaValidator.missingColumns(TaskRecord.class)).isEmpty();
```

### Schema drift detection

`missingColumns` checks column existence only. Use `typeDrift`, `nullabilityDrift`, or the combined `schemaDrift` check:

```java
assertThat(schemaValidator.typeDrift(TaskRecord.class)).isEmpty();
assertThat(schemaValidator.nullabilityDrift(TaskRecord.class)).isEmpty();
assertThat(schemaValidator.schemaDrift(TaskRecord.class)).isEmpty();
```

Example finding for `typeDrift`:

```
task_record.amount: mapped as DECIMAL (BigDecimal) but database column is INTEGER (int4)
```

Example finding for `nullabilityDrift`:

```
task_record.status: mapping declares NOT NULL but database column is nullable
```

`schemaDrift` returns the concatenated results of all three checks (`missingColumns`, `typeDrift`, and `nullabilityDrift`). When the table itself is absent, it returns only the table-not-found message.

**Limits** — these are deliberate design decisions, not bugs:

- Types are compared by **FAMILY** (character / integer / decimal / floating / boolean / date / time / timestamp / binary), not by vendor type name, so `varchar` vs `text` is not drift and neither is `varchar(50)` vs `varchar(255)` — length and precision are not compared at all.
- Nullability reports only **ONE direction**: mapping claims NOT NULL while the database allows NULL. The other direction is undetectable, because JPA's `nullable = true` is the annotation default and reflection cannot tell "the author wrote `nullable = true`" from "the author wrote nothing". Say this explicitly — a mapping that says nothing about nullability is a non-statement and is never reported.
- UUID and any unresolvable field type are silent by design (UUID storage is vendor-specific), as are relationship columns for type drift.
- Why: a drift check that reports forty findings against a healthy schema gets switched off within a week, and then reports nothing forever.

Optional repository convention:

```java
interface TaskRecordRepository extends JpaRepository<TaskRecord, Long> {
    Optional<TaskRecord> findByTaskId(String taskId);

    default TaskRecord waitByTaskId(String taskId) {
        throw new UnsupportedOperationException("Handled by TestForge");
    }
}
```

## Named datasources

Named datasources are ordinary Spring `DataSource` beans, addressed by bean
name. A project with a single datasource needs no change.

```java
@Bean
@Primary
DataSource primaryDataSource() {
    return DataSourceBuilder.create()
            .url("jdbc:postgresql://primary-host/mydb").build();
}

@Bean
DataSource auditDataSource() {
    return DataSourceBuilder.create()
            .url("jdbc:postgresql://audit-host/auditdb").build();
}
```

Usage:

```java
dbWaiter.on("auditDataSource").awaitRowCount(
        "audit entry for " + taskId,
        "SELECT count(*) FROM audit_log WHERE task_id = '" + taskId + "'",
        1);

assertThat(schemaValidator.forDataSource("auditDataSource")
        .missingColumns(AuditEntry.class)).isEmpty();
```

Default resolution order: `forge.db.default-datasource` property if set →
unique `@Primary` bean → the single `DataSource` bean when exactly one
exists. A single-datasource project needs no configuration change.

## Adapting to a project

Add JPA entities + Spring Data repositories for the service tables your tests
assert on. If there are many services, give each service DB its own Gradle
module so teams can own their mappings. `SchemaValidator` resolves `@Column`,
`@JoinColumn` and `@Embedded` (with `@AttributeOverride`); inheritance and
custom naming strategies are not resolved — extend it first for entities that
use those (limitations listed in its Javadoc).

## Agent notes

- One entity + repository per asserted table; a `SchemaValidator` test per
  entity belongs in a scheduled CI job.
- `SchemaValidator` resolves `@Column`/`@JoinColumn`/`@Embedded` (with
  `@AttributeOverride`); it does NOT resolve inheritance or custom naming
  strategies — extend it first if the project uses those.
- `waitBy...` repository methods only work with
  `forge.db.repository-polling.enabled: true`; the marker default method must
  throw, never return a stub value.
- `awaitRow`/`awaitRows` stay datasource-agnostic because they poll a
  caller-supplied supplier; only `awaitRowCount` and `forDataSource` select a
  database.
- `missingColumns` keeps its old narrow meaning and does NOT return type or nullability findings — use `schemaDrift` for everything.

