# module-db type and nullability schema drift design

This note is the contract for the schema-drift feature. Every task is written
against it. Read it before changing anything under `module-db/`.

## Problem

`SchemaValidator` answers exactly one question today: *does this mapped column
exist?* It cannot see that a column it found has since become an `INTEGER` where
the mapping still expects text, or that a column the mapping declares
`nullable = false` is in fact nullable in the database.

Both are silent failures. The mapping keeps compiling, the existing
`missingColumns` check keeps returning an empty list, and the drift is found by a
production incident instead of by CI.

## Scope

Column **type family** drift and **nullability** drift, for entity mappings
`SchemaValidator` already resolves.

Explicitly **not** in this feature: indexes, foreign-key constraints, naming
strategies, inheritance, migrations, Flyway/Liquibase, default values, collation,
check constraints, unique constraints, column ordering, multi-datasource work
(already delivered), and any unrelated refactor of `module-db`.

## The governing constraint: silence beats noise

A drift check that reports forty findings against a healthy schema gets switched
off within a week, and then reports nothing forever. So the rule for this whole
feature is:

> Report only where the mapping makes an explicit claim that the database
> demonstrably contradicts. Anything unknown, unmapped, or unstated is **not**
> drift and must stay silent.

Two consequences, both deliberate, both must be documented as limits:

1. **Type comparison is by family, not by vendor type name.** The driver already
   normalises `varchar` / `character varying` / `VARCHAR` to `Types.VARCHAR`.
   Comparing `TYPE_NAME` strings would fire on every H2-to-Postgres difference
   and prove nothing. Length and precision are **not** compared: `varchar(50)`
   vs `varchar(100)` is not drift for this feature.
2. **Only the "mapping says NOT NULL, database says nullable" direction is
   reported.** JPA's `nullable = true` is the annotation's default, so
   reflection cannot distinguish "the author wrote `nullable = true`" from "the
   author wrote nothing at all". A silent mapping is a non-statement, not an
   assertion, and is never reported.

## Type families

A pure, dependency-free mapping in a new
`io.testforge.db.schema.ColumnTypeFamily` enum:

`CHARACTER`, `INTEGER`, `DECIMAL`, `FLOATING`, `BOOLEAN`, `DATE`, `TIME`,
`TIMESTAMP`, `BINARY`, `UNKNOWN`.

From `java.sql.Types` (what `DatabaseMetaData.getColumns` reports in `DATA_TYPE`):

| Family | `java.sql.Types` |
|---|---|
| `CHARACTER` | `CHAR`, `VARCHAR`, `LONGVARCHAR`, `NCHAR`, `NVARCHAR`, `LONGNVARCHAR`, `CLOB`, `NCLOB` |
| `INTEGER` | `TINYINT`, `SMALLINT`, `INTEGER`, `BIGINT` |
| `DECIMAL` | `DECIMAL`, `NUMERIC` |
| `FLOATING` | `REAL`, `FLOAT`, `DOUBLE` |
| `BOOLEAN` | `BOOLEAN`, `BIT` |
| `DATE` | `DATE` |
| `TIME` | `TIME`, `TIME_WITH_TIMEZONE` |
| `TIMESTAMP` | `TIMESTAMP`, `TIMESTAMP_WITH_TIMEZONE` |
| `BINARY` | `BINARY`, `VARBINARY`, `LONGVARBINARY`, `BLOB` |
| anything else | `UNKNOWN` |

`BIT` sits with `BOOLEAN` on purpose: the PostgreSQL driver reports `bool` as
`Types.BIT`, and treating that as drift against an H2 `BOOLEAN` would be a
false positive on the one vendor pair this project actually cares about.

From Java field types:

| Family | Java types |
|---|---|
| `CHARACTER` | `String`, `char`, `Character` |
| `INTEGER` | `byte`, `short`, `int`, `long` + boxed, `BigInteger` |
| `DECIMAL` | `BigDecimal` |
| `FLOATING` | `float`, `double` + boxed |
| `BOOLEAN` | `boolean`, `Boolean` |
| `DATE` | `LocalDate`, `java.sql.Date` |
| `TIME` | `LocalTime`, `java.sql.Time` |
| `TIMESTAMP` | `Instant`, `LocalDateTime`, `OffsetDateTime`, `ZonedDateTime`, `java.util.Date`, `java.sql.Timestamp` |
| `BINARY` | `byte[]` |
| anything else | `UNKNOWN` |

Enums resolve through `@Enumerated`: `STRING` → `CHARACTER`, `ORDINAL` (the JPA
default) → `INTEGER`.

**`UUID` maps to `UNKNOWN` deliberately.** Drivers disagree (`Types.OTHER`,
`BINARY`, `CHAR`), so any rule here would produce false positives.

**Relationship columns are skipped.** A `@ManyToOne` / `@JoinColumn` FK column's
type is the *target* entity's `@Id` type; resolving that means walking the
entity graph, which is the ORM-internals rabbit hole the backlog warns against.
They still participate in `missingColumns`, as they do today.

### The silence rule, mechanically

If **either** side resolves to `UNKNOWN`, report nothing. Unknown is not drift.

## Nullability

The mapping is treated as claiming NOT NULL when any of these is present:
`@Column(nullable = false)`, `@JoinColumn(nullable = false)`,
`@Basic(optional = false)`, or `@Id` (a primary key is implicitly NOT NULL).

Report when that claim holds and `DatabaseMetaData.getColumns` reports
`IS_NULLABLE = YES` for the column. Nothing else is reported.

## Public API

Three methods, in the existing style — `missingColumns` already returns a list
of human-readable problems, and these match it:

```java
public List<String> typeDrift(Class<?> entityClass)
public List<String> nullabilityDrift(Class<?> entityClass)
public List<String> schemaDrift(Class<?> entityClass)   // missing + type + nullability
```

An empty list means "in sync". When the table itself is absent, `schemaDrift`
returns only the existing "table '...' not found in database" message rather
than cascading a finding per column.

Message shapes:

```
task_record.task_id: mapped as CHARACTER (String) but database column is INTEGER (int4)
task_record.task_id: mapping declares NOT NULL but database column is nullable
```

Each message names the table, the column, and both sides. A finding a reader
cannot act on without opening a debugger is a bad finding.

## Backward compatibility

`missingColumns(Class<?>)`, both constructors, and `forDataSource(String)` keep
their signatures and behaviour exactly. `missingColumns` must not start
reporting type or nullability problems — consumers run it in scheduled CI jobs
and a widened meaning would break them. The internals that read column metadata
are refactored to carry type and nullability alongside the name; that refactor
must not change what `missingColumns` returns.

## Test strategy

- unit tests for the family mapping, both directions, including `BIT`/`BOOLEAN`,
  the enum cases, and that unknown maps to `UNKNOWN`;
- H2 tests for real drift: a column whose type family genuinely changed, and a
  column that is nullable in the database while the mapping declares NOT NULL;
- **negative tests are the important half**: a healthy schema reports nothing; a
  `varchar(50)` vs `varchar(255)` difference reports nothing; a silent mapping
  against a `NOT NULL` column reports nothing; an unmappable type reports
  nothing;
- cross-vendor proof: extend the existing `PostgresSchemaValidationIT`
  (`containers` tag) so the family comparison is shown to hold on the vendor the
  services actually run, not only on H2. Postgres `bool` → `Types.BIT` is the
  specific case that would break a naive implementation.
