# module-db

Database-level assertions for gray-box testing: verify not only what the API
answered, but what the services actually persisted.

## What's inside

- **`DbWaiter`** — polls a repository call until the row written by an
  asynchronous backend process appears. The antidote to `Thread.sleep`.
- **`RepositoryPollingAspect`** — optional naming-convention wrapper:
  `waitBy...` default repository methods poll the matching `findBy...` query.
- **`SqlLoggingDataSourcePostProcessor`** — logs every SQL statement tests
  execute (logger `forge.sql`), enabled by `forge.db.log-sql: true`.
- **`SchemaValidator`** — compares an entity's mapped columns against the real
  database. Catches service migrations that silently break the test
  framework's mappings. Run one test per entity in a scheduled CI job.

## Configuration

```yaml
forge:
  db:
    log-sql: true   # default: false
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
