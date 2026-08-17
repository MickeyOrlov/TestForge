# module-db multi-datasource design

This note is the contract for the multi-datasource feature. Every task in this
feature is written against it. Read it before changing anything under
`module-db/`.

## Problem

`module-db` assumes exactly one database.

- `SchemaValidator` is constructed with a single `javax.sql.DataSource` and has
  no way to look at any other one.
- `DbWaiter` has no `DataSource` at all. It wraps `core`'s `Waiter` and polls a
  caller-supplied `Supplier`, so "which database" is whatever the caller's
  repository happens to point at. There is no way for a consumer to say
  "wait against the audit database".

A test framework asserting against a system with several databases (a service
DB plus an audit DB, or one DB per service) therefore cannot express which
database a check belongs to.

## Scope

Multi-datasource selection only. Explicitly **not** in this feature: schema type
drift, nullability drift, indexes, foreign keys, naming strategies, inheritance,
migrations, Flyway/Liquibase, tenant routing, dynamic runtime routing,
read/write splitting, connection-pool abstraction, transaction-manager
abstraction, a new database DSL, and any unrelated refactor of `module-db`.

## Configuration strategy: bean names, not a second config system

Named datasources are **Spring `DataSource` beans, addressed by bean name**.
`module-db` has never owned database connection configuration — it consumes
whatever `DataSource` the application context provides (Spring Boot's
`spring.datasource`, or the consumer's own `@Bean`). A consumer that wants a
second database declares a second `DataSource` bean, exactly as it would for any
Spring application:

```java
@Bean
@Primary
DataSource primaryDataSource() { ... }

@Bean
DataSource auditDataSource() { ... }
```

Inventing `forge.db.datasources.<name>.url/username/password` was rejected: it
would duplicate `spring.datasource`, drag a connection-pool decision into
`module-db`, and give consumers two competing places to configure the same
thing. The design gate forbids both a second configuration system and a
connection-pool abstraction.

Exactly **one** new property is added:

```yaml
forge:
  db:
    default-datasource: primaryDataSource   # optional; bean name
```

## The seam

```
Spring DataSource beans (by bean name)
        |
        v
DataSourceRegistry  --  resolveDefault() / resolve(name) / names()
        |
        +--> DbWaiter.on(name)
        +--> SchemaValidator.forDataSource(name)
```

### `io.testforge.db.datasource.DataSourceRegistry`

A plain, immutable, thread-safe lookup. It does no Spring introspection itself —
the autoconfiguration computes the default name and hands it over, which keeps
the registry unit-testable without a context.

```java
public DataSourceRegistry(Map<String, DataSource> dataSources, String defaultName)
public DataSource resolve(String name)   // null/blank -> resolveDefault()
public DataSource resolveDefault()
public String defaultName()
public Set<String> names()               // sorted, immutable
```

Semantics:

- the map is copied defensively into a name-sorted immutable map;
- a non-blank `defaultName` that is not a known name fails **at construction**,
  listing the known names;
- `resolve(unknown)` throws `IllegalArgumentException` naming the bad name and
  listing the configured names;
- `resolveDefault()` with no default and more than one datasource throws
  `IllegalStateException` telling the consumer to set
  `forge.db.default-datasource` or mark a bean `@Primary`;
- `resolveDefault()` with no datasources at all throws `IllegalStateException`.

### Default resolution order (deterministic)

1. `forge.db.default-datasource`, if set — must name an existing bean;
2. otherwise the unique `@Primary` `DataSource` bean;
3. otherwise the single `DataSource` bean, if there is exactly one;
4. otherwise there is no default, and `resolveDefault()` fails with the
   actionable message above.

Rule 3 is what preserves today's behaviour: a single-datasource application gets
the same `DataSource` it gets now, with no configuration change.

## Backward compatibility

Both public types keep their existing constructors and existing methods with
unchanged signatures and semantics.

- `SchemaValidator(DataSource)` — retained. An instance built this way is bound
  to that one `DataSource` and behaves exactly as it does today.
  `missingColumns(Class)` is unchanged.
- `DbWaiter(Waiter)` — retained. `awaitRow` / `awaitRows` are unchanged and stay
  datasource-agnostic: they poll a caller-supplied supplier, so the datasource
  is whatever the caller's repository uses. A `DbWaiter` built this way has no
  registry; calling a datasource-aware method on it fails with an
  `IllegalStateException` explaining that no `DataSourceRegistry` is available.

No existing configuration key changes meaning. `forge.db.log-sql` and
`forge.db.repository-polling.enabled` are untouched.

## New public API

### `DbWaiter`

```java
public DbWaiter(Waiter waiter, DataSourceRegistry registry)   // new
public DbWaiter on(String datasourceName)                     // named view
public long awaitRowCount(String description, String sql, int minCount)
```

`awaitRowCount` polls `sql` against the resolved `DataSource` and reads the
first column of the first row as a `long`, until it is `>= minCount` or the
`forge.wait` timeout expires. It is deliberately the *only* JDBC-backed wait
added: it is the minimum needed to make "wait against **this** database" mean
anything, and it is what makes real cross-database isolation provable. No query
builder, no row mapper interface, no fluent DSL — those are a database DSL,
which is a non-goal.

`on(name)` returns a `DbWaiter` bound to the named datasource; the returned
instance is independent and immutable. `on(null)`/`on("")` binds the default.

### `SchemaValidator`

```java
public SchemaValidator(DataSourceRegistry registry)           // new
public SchemaValidator forDataSource(String name)             // named view
```

`forDataSource(name)` returns a validator bound to the named datasource.
`missingColumns` on a registry-backed validator resolves the default datasource.

## Autoconfiguration

`TestForgeDbAutoConfiguration` gains a `DataSourceRegistry` bean, built by
inspecting the bean factory for `DataSource` beans and their `@Primary` flags,
combined with `forge.db.default-datasource` from a new
`@ConfigurationProperties("forge.db")` record. It is `@ConditionalOnMissingBean`
and `@ConditionalOnBean(DataSource.class)`, so an application with no datasource
is unaffected.

`schemaValidator` is built from the registry instead of a raw `DataSource`
(same conditions as today). `dbWaiter` takes the registry via `ObjectProvider`,
so `DbWaiter` still exists in a context with no `DataSource` — as it does today.

### Known trade-off: the registry is built eagerly

The registry bean calls `getBeansOfType(DataSource.class)`, so every
`DataSource` bean is instantiated when the context starts — including a
`@Lazy` one. This is not a regression for existing consumers (the previous
`schemaValidator(DataSource)` bean already forced the single datasource), but a
multi-datasource consumer who marks an expensive datasource `@Lazy` will see it
created at startup anyway.

Deferring it would need either a second construction path on the registry
(a `Map<String, Supplier<DataSource>>`), or dropping `final` from
`DataSourceRegistry` so Spring could inject a `@Lazy` CGLIB proxy — CGLIB
cannot subclass a final class. Both were judged out of scope for this feature;
the immutability of the registry is worth more than deferred instantiation of a
`DataSource` object, which opens no connections on construction. Revisit if a
consumer reports a real cost.

## Test strategy

`module-db` gains its own `src/test` (test-scope dependencies only; nothing is
added to the published API). Proof required:

- **real isolation**: two genuinely distinct in-memory H2 databases
  (`jdbc:h2:mem:tf_primary` and `jdbc:h2:mem:tf_audit`). A table/row that exists
  only in `primary` must be observed through `primary` and must **not** satisfy
  the same check through `audit`. Mocks do not prove this and are not accepted
  for it;
- **compatibility**: the legacy single-datasource path (existing constructors,
  existing methods, existing configuration) still works;
- **errors**: unknown name, no default with several datasources, and a bad
  `forge.db.default-datasource` all fail with the messages specified above;
- **Spring**: named beans resolve, `@Primary` wins, and single-datasource
  autoconfiguration stays green.

Negative waits (a condition that must *not* be satisfied) set a short
`forge.wait.timeout` so the suite stays fast.
