# module-db

Database-level assertions for gray-box testing: verify not only what the API
answered, but what the services actually persisted.

## What's inside

- **`DbWaiter`** — polls a repository call until the row written by an
  asynchronous backend process appears, or SQL until a value or count is
  reached (`awaitValue`, `awaitRowCount`). The antidote to `Thread.sleep`.
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

- **`DbState`** — `into(table).insert(row)` for setup rows removed after the
  test (`module-data` `Cleanup`), and table snapshots in plain JDBC: what an action changed, row
  by row and column by column, with expectations that fail with the whole
  diff (`changed(1, "status")` fails when `total` changed too).

## Configuration

```yaml
forge:
  db:
    log-sql: true   # default: false
    default-datasource: primaryDataSource  # optional; bean name
    repository-polling:
      enabled: true # default: false
    diagnostics:
      enabled: true # default: false; SQL of failed tests in the run index
      last: 20      # statements kept per test thread
```

## Usage

```java
TaskRecord row = dbWaiter.awaitRow(
        "task_record for task " + taskId,
        () -> taskRepository.findByTaskId(taskId));

assertThat(schemaValidator.missingColumns(TaskRecord.class)).isEmpty();
```

Waiting for a value in SQL, without a repository — values always as `?`
parameters:

```java
dbWaiter.awaitValue("order " + id + " paid",
        "SELECT status FROM orders WHERE id = ?", "PAID", id);
dbWaiter.awaitRowCount("audit entries for " + id,
        "SELECT count(*) FROM audit_log WHERE order_id = ?", 2, id);
```

On timeout the failure says what the database last answered — `expected
PAID, last saw PENDING`, `last saw no row`, or `the query failed: Table
"AUDIT_LOG" not found` — instead of only that time ran out. Numbers compare
by value (`1` equals `1L`).

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

## What an action changed: table snapshots

`StateSnapshot` (core) compares entities. `DbState` compares rows, so no
entity, repository or content-based `equals` is needed:

```java
@Autowired DbState db;

TableSnapshot before = db.table("orders").keyedBy("id")
        .where("customer_id = ?", customerId)      // only this test's rows
        .ignoring("updated_at")                     // changes that do not count
        .take();

api.request().post("/orders/{id}/pay", orderId);

before.diff().expect().added(0).removed(0).changed(1, "status", "paid_at");
```

A failed expectation carries the whole diff:

```
columns changed that should not have: [total] in orders where customer_id = ? (keyed by id)
  ~ id=41 status: NEW → PAID; total: 10 → 0
```

- `+` added, `-` removed, `~` changed rows; `diff()` reads the same rows
  again, so call it after the asynchronous write is done (`DbWaiter`).
- Keys can span columns (`keyedBy("order_id", "line_no")`). Decimals compare
  by value (`10.00` = `10.0`); LOBs are not compared.
- On a shared database always narrow with `where(...)`: a whole-table
  snapshot sees every parallel test's writes. More than 10 000 rows fails
  (`maxRows(...)` to raise it).
- Table and column names must be plain identifiers; the `where` clause is
  the test's own SQL with `?` parameters.
- `db.on("auditDataSource")` for a named datasource.

### Exactly what changed in a row

`changed(1, "status")` says one row changed in one column. A row expectation
says which row, from what to what, and that nothing else in it moved:

```java
before.diff().expect().changed(1)
        .row(42)                                   // key values in keyedBy order
            .changed("status", "NEW", "PAID")
            .increasedBy("version", 1)
            .movedForward("updated_at")            // works on ignoring(...) columns too
            .unchanged("created_at", "customer_id")
            .onlyThese()                           // any other column of row 42 that moved fails
        .and()
        .row(41).unchanged("status")               // the neighbour stayed as it was
        .and()
        .addedRow(43).has("status", "NEW").has("total", 3)
        .and()
        .removedRow(40);
```

Values compare by meaning — `42` matches an `INTEGER`, `BIGINT` or `NUMERIC`,
`10` matches `10.00`, an enum matches its name, a `LocalDateTime` matches a
`TIMESTAMP`. A column listed in `ignoring(...)` never makes a row count as
changed, but its move is kept, so `movedForward("updated_at")` still checks
it. Every failure names the row and the column and carries the whole diff:

```
row id=42: status expected NEW → SHIPPED, was NEW → PAID in orders where customer_id = ? (keyed by id)
  ~ id=42 status: NEW → PAID; version: 3 → 4
```

### Nothing else moved: several tables at once

A diff of one table cannot see the cascade delete, the second audit row or the
stray update in a table the test never looked at. Watch every table the action
might touch, then name the ones it should:

```java
DbSnapshot before = db.watch(
        db.table("orders").keyedBy("id").where("customer_id = ?", customer),
        db.table("payments").keyedBy("id").where("customer_id = ?", customer),
        db.table("loyalty_points").keyedBy("customer_id").where("customer_id = ?", customer));

api.pay(order);

before.diff().expect()
        .table("orders", t -> t.row(order).changed("status", "NEW", "PAID").onlyThese())
        .table("payments", t -> t.added(1))
        .nothingElse();          // loyalty_points must not have moved
```

`nothingElse()` fails on any watched table without expectations that changed;
`onlyChanged("orders", "payments")` says the same without per-table checks. The
failure lists the tables and their diffs:

```
tables changed that the checks did not name: [loyalty_points]
loyalty_points where customer_id = ? (keyed by customer_id)
  ~ customer_id=7 points: 100 → 0
```

On a database the test owns — embedded, or a container per run —
`db.watchSchema("flyway_schema_history")` watches every table of the
connection's schema, keyed by its primary key (a table without one is matched
on all its columns). On a shared database every parallel test's writes would
count there; use `watch(...)` with `where` clauses instead.

## Rules the data must keep

A backend can write exactly the row a test expected and still break the data
around it: a retry inserts the order twice, a delete leaves items behind, a new
status sneaks in. `db.invariants()` checks rules the schema may not enforce:

```java
db.invariants()
        .within("orders", "customer_id = ?", customer)      // the test's own rows on a shared database
        .noOrphans("order_items.order_id", "orders.id")
        .unique("orders", "external_id")
        .allowedValues("orders", "status", Status.NEW, Status.PAID, Status.CANCELLED)
        .notNull("orders", "customer_id", "created_at")
        .holds("orders", "total >= 0")
        .assertHolds();
```

Every rule runs; the failure lists each broken one with how many rows (or
distinct values) broke it and up to five of them:

```
2 of 5 invariant(s) broken:
  - no orphans: every order_items.order_id exists as orders.id: 1 row
      {id=101, order_id=2, sku=B}
  - unique: orders(external_id): 1 value
      {external_id=ext-1, duplicates=2}
```

`within(table, condition, params...)` narrows every rule reading that table —
the child side of an orphan check included. `verify()` returns the report
without throwing. Run the same rules after a scenario, or over the whole
database from a scheduled job (no `within`) to catch what any test left.

## Rows for setup, removed after the test

For a state the product's API cannot produce, or cannot produce fast:

```java
@ExtendWith(CleanupExtension.class)   // module-data
class ArchivedOrdersTest {

    @Autowired DbState db;

    @Test
    void archivedOrdersAreReadOnly() {
        Map<String, Object> customer = db.into("customers").keyedBy("id")
                .insert(Map.of("name", "c-" + UUID.randomUUID()));
        db.into("orders").keyedBy("id")
                .insert(Map.of("customer_id", customer.get("id"), "status", "ARCHIVED"));
        ...
    }
}
```

- `insert` returns the row's key — from the values given, or from the keys
  the database generated.
- Its `DELETE` is registered with `Cleanup` the moment the insert succeeds and
  runs after the test, even a failed one, newest first: a child row inserted
  after its parent is removed before it. `withoutCleanup()` leaves it.
- Prefer the API for setup; direct inserts skip the product's own rules and
  are an explicit choice of the test.

## When a test fails: the SQL it ran

```java
@SpringBootTest(properties = "forge.db.diagnostics.enabled=true")
@ExtendWith(DbDiagnosticsExtension.class)
class OrdersTest { ... }
```

A failed test publishes `module-db/<Class>-<method>.md` to the run index: the
last statements of its thread, oldest first, with the datasource, duration,
bound parameters (one line per batch entry) and the error of a statement that
failed. The original failure is rethrown unchanged; passing tests write
nothing. `SqlJournal.recent()` gives the same list to a test directly.

- Off by default, unlike HTTP diagnostics: recording means wrapping the
  DataSource bean in a proxy, the same one `log-sql` uses (one proxy serves
  both). Code that casts the bean to a pool type breaks under it.
- Parameter values are shown as bound (shortened to 200 characters): they are
  test data. Keep real secrets out of SQL parameters, or leave diagnostics off
  where they are.

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
        "SELECT count(*) FROM audit_log WHERE task_id = ?",
        1, taskId);

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
`@JoinColumn`, `@Embedded` (with `@AttributeOverride`), `@MappedSuperclass`
fields (renamed by a class-level `@AttributeOverride`) and entity inheritance:

| Root `@Inheritance` | Table validated | Columns expected |
|---|---|---|
| `SINGLE_TABLE` (default) | the root's table | every field of the chain + discriminator (`@DiscriminatorColumn`, default `DTYPE`) |
| `JOINED` | the entity's own table | its own fields + the key (`@PrimaryKeyJoinColumn`, default the root's id column) |
| `TABLE_PER_CLASS` | the entity's own table | every field of the chain |

Implicit names (no explicit `@Column`/`@Table` name) follow the service's ORM
naming. Match it once:

```yaml
forge:
  db:
    schema-naming: snake-case   # default: Spring Boot's CamelCaseToUnderscores
    # schema-naming: as-is      # Hibernate's JPA-compliant standard naming
```

Anything else is a `SchemaNamingStrategy` bean; `schemaValidator.withNaming(...)`
switches it for one call site. Explicit names are always taken as written.
`@SecondaryTable` and `@AssociationOverride` are not resolved.

### Indexes and foreign keys

`indexDrift(Entity.class)` and `foreignKeyDrift(Entity.class)` (both included in
`schemaDrift`) catch a constraint your tests rely on disappearing from the
service's database:

```
users: mapping declares a unique index on (email) but the database has none
orders.customer_id: foreign key points to legacy_customers.id but the mapping expects customers
```

- **Indexes** come from `@Table(indexes)`, `@Table(uniqueConstraints)` and
  `@Column(unique = true)`, and are matched by columns, never by name —
  generated index names differ between databases. A declared unique index
  needs a unique database index on exactly that column set (any order); a
  declared index needs a database index that starts with those columns, in
  order. `DESC`/`ASC` in `columnList` is ignored.
- **Foreign keys** are expected only where the mapping declares one:
  `@JoinColumn(foreignKey = @ForeignKey(name = "fk_customer"))` or
  `@ForeignKey(ConstraintMode.CONSTRAINT)`. Many service databases skip FKs on
  purpose, so a plain `@ManyToOne` expects nothing by default. For a service
  whose ORM creates them, expect one behind every join column:

  ```yaml
  forge:
    db:
      schema-foreign-keys: all     # default: declared
  ```

  `ConstraintMode.NO_CONSTRAINT` is never expected. `referencedColumnName` is
  compared when set.
- One-directional, like nullability: extra indexes and keys in the database
  are never reported.

## Agent notes

- One entity + repository per asserted table; a `SchemaValidator` test per
  entity belongs in a scheduled CI job.
- `SchemaValidator` resolves `@Column`/`@JoinColumn`/`@Embedded`, mapped
  superclasses and the three inheritance strategies; set
  `forge.db.schema-naming` (or a `SchemaNamingStrategy` bean) to the
  service's ORM naming before trusting "missing column" findings.
  `@SecondaryTable` and `@AssociationOverride` are not resolved.
- Declare the indexes and foreign keys a test relies on (`@Column(unique)`,
  `@Table(indexes)`, `@ForeignKey`): only declared ones are checked, so an
  undeclared constraint is invisible to drift detection.
- `waitBy...` repository methods only work with
  `forge.db.repository-polling.enabled: true`; the marker default method must
  throw, never return a stub value.
- `awaitRow`/`awaitRows` stay datasource-agnostic because they poll a
  caller-supplied supplier; only `awaitRowCount` and `forDataSource` select a
  database.
- `missingColumns` keeps its old narrow meaning and does NOT return type or nullability findings — use `schemaDrift` for everything.

