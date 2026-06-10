# module-db

Database-level assertions for gray-box testing: verify not only what the API
answered, but what the services actually persisted.

## What's inside

- **`DbWaiter`** — polls a repository call until the row written by an
  asynchronous backend process appears. The antidote to `Thread.sleep`.
- **`RepositoryWaiterAspect`** — optional naming-convention wrapper:
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
    repository-waiter:
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
module so teams can own their mappings. Known `SchemaValidator` limitations
are listed in its Javadoc — extend it before relying on it for entities with
`@Embedded`, inheritance or custom naming strategies.
