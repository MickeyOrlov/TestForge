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
  database. Catches service migrations that silently break the test
  framework's mappings. Run one test per entity in a scheduled CI job.
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
