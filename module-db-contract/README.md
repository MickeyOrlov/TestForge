# module-db-contract

Database schema as a versioned contract: capture the schema, diff it against a
baseline, and tell CI whether the change **breaks a consumer** — not merely that
something changed.

`module-db` answers "did the service persist what I expected?".
`module-db-contract` answers "did the service's schema move under my tests, and
does that move break them?".

## What's inside

- **`DbContractRunner`** — the whole pipeline: inspect → snapshot → diff →
  classify → report → gate. `assertCompatible()` is the CI assertion.
- **`SchemaCrawlerDbSchemaInspector`** — reads the schema through
  [SchemaCrawler](https://www.schemacrawler.com/), keeping only what the model
  below needs. TestForge writes no `DatabaseMetaData` crawler of its own.
- **`DbSchemaSnapshot`** and friends (`DbTable`, `DbColumn`, `DbPrimaryKey`,
  `DbForeignKey`, `DbIndex`) — the normalized model. Small on purpose, and the
  reason SchemaCrawler's API never becomes TestForge's API.
- **`DbSchemaSnapshotStore`** — deterministic, timestamp-free JSON, so a
  committed baseline changes in git exactly when the contract does.
- **`DbSchemaComparator`** — bounded diff between two snapshots. No SQL parsing,
  no migration scripts.
- **`DefaultDbCompatibilityPolicy`** — turns each change into
  `BREAKING` / `RISKY` / `NON_BREAKING` / `UNKNOWN`, with a reason.

```text
DataSource → SchemaCrawler → DbSchemaSnapshot → snapshot file
                                     ↓
                         baseline ↔ current diff
                                     ↓
                          compatibility policy
                                     ↓
        BREAKING / RISKY / NON_BREAKING / UNKNOWN → report + CI gate
```

## Configuration

```yaml
forge:
  db-contract:
    enabled: true            # default: false — the module never touches a database on its own
    schema: public           # the schema the contract is defined on
    datasource: ""           # optional bean name; blank uses the default datasource
    baseline-file: build/db-contract/baseline/schema-snapshot.json
    output-dir: build/db-contract
    include-tables: ""       # full-match regex on plain table names
    exclude-tables: "flyway_schema_history|databasechangelog"
    fail-on:
      breaking: true         # default
      risky: false           # default
      unknown: false         # default
```

## Usage

```java
// one scheduled or review CI job, not the default build
DbContractReport report = dbContractRunner.assertCompatible();
```

Bootstrapping a baseline, and promoting it after a reviewed change:

```java
dbContractRunner.writeBaseline();   // explicit, never a side effect of run()
```

`run()` never rewrites the baseline. A schema change cannot make itself
disappear by being re-recorded — promoting a new baseline is always a separate,
deliberate call.

**`assertCompatible()` fails closed when there is no baseline.** An enabled check
with nothing to compare against is a job that believes it is gating and is not,
which is the failure this module exists to remove. That is raised as a
configuration failure — an `IllegalStateException`, like a missing schema or a
foreign snapshot format — and deliberately not as a schema verdict: nothing was
compared, so calling it breaking, risky or unknown would be a claim about a
schema the run never looked at.

`run()` is the other half of that split and stays usable with no baseline: it
captures the schema, writes the reports, says `baselinePresent: false` in both of
them, and returns normally. Bootstrapping a new project therefore means `run()`
or `writeBaseline()`, never a gate that quietly passes.

A failing gate names the change and the reason:

```
Database contract check failed for schema 'public': 1 breaking, 0 risky, 0 unknown change(s).
  - [BREAKING] COLUMN_REMOVED orders.amount: Every consumer selecting this column now fails.
Report: build/db-contract/report.md
```

## Compatibility rules (v1)

The "consumer" is a test suite or service that reads and writes this schema by
column name.

| Change | Verdict |
|---|---|
| Table added | `NON_BREAKING` |
| Table removed, column removed | `BREAKING` |
| Column added, nullable | `NON_BREAKING` |
| Column added, NOT NULL with a default | `RISKY` |
| Column added, NOT NULL without a default | `BREAKING` |
| Logical type family changed (both sides mapped) | `BREAKING` |
| Logical type family changed (either side unmapped) | `UNKNOWN` |
| Physical type changed within the same family | `RISKY` |
| nullable → NOT NULL | `BREAKING` |
| NOT NULL → nullable | `RISKY` |
| Default added / removed | `NON_BREAKING` / `RISKY` |
| Primary key added | `RISKY` |
| Primary key removed or re-keyed | `BREAKING` |
| Foreign key added / removed | `RISKY` |
| Foreign key retargeted | `BREAKING` |
| Referential action starts rejecting (`CASCADE` → `RESTRICT`/`NO ACTION`) | `BREAKING` |
| Any other referential action change | `RISKY` |
| Index added, non-unique | `NON_BREAKING` |
| Index added unique, dropped, re-columned, uniqueness flipped | `RISKY` |
| Index predicate gained, lost or altered | `RISKY` |

`UNKNOWN` is not a severity. It means the change was not classified, so it is
neither worse nor better than `RISKY`: it never contributes to the report's
`worstClassified` verdict, and it has its own gate (`fail-on.unknown`),
independent of `fail-on.risky`.

To gate differently, register your own `DbCompatibilityPolicy` bean — the
default one is `@ConditionalOnMissingBean`.

## The v1 boundary

What the model carries: schemas, tables, columns (logical type family, physical
type, nullability, presence of a default), primary keys, foreign keys with their
referential actions, and indexes with their partial predicates.

What it deliberately does not carry: views, triggers, routines, sequences,
partition keys and bounds, comments, privileges, collations, default
*expressions*, constraint deferrability, index type and cardinality, and
physical column order.

Other deliberate limits, all of them visible in the tests:

- **Objects are matched by name.** A renamed table, column, index or foreign key
  reports as a removal plus an addition — that is the honest reading of two
  snapshots with no rename log between them.
- **Primary-key constraint names are recorded but not compared.** Renaming the
  constraint shows in the snapshot's git diff and in no contract change.
- **The unique index backing a primary key is dropped** from the model, so
  adding a primary key is reported once rather than twice.
- **Identifiers are stored exactly as the database reports them** (PostgreSQL
  folds to lower case, H2 to upper). Snapshots are comparable within one
  database lineage, not across vendors. Line endings, on the other hand, are
  pinned to `\n` on every platform, so a baseline captured on Linux does not
  rewrite itself the first time someone runs the check on Windows.
- **A foreign key pointing outside the inspected schema records its target as
  `schema.table`**; same-schema keys keep the bare name. Without that,
  retargeting a key from `public.orders` to `archive.orders` would diff as no
  change at all.
- **A PostgreSQL partitioned parent is part of the contract**, because that is
  the table consumers query. Its child partitions are crawled as ordinary
  tables, so a project that rotates partitions should exclude them —
  `exclude-tables: "events_\\d{4}"` — or every rotation reports a `BREAKING`
  table removal.
- **`hasDefault` means "the database supplies a value when the writer omits the
  column"** — a DEFAULT clause, an identity definition or a generated expression
  all count. PostgreSQL keeps identity outside `COLUMN_DEF`, so reading only the
  default clause called a harmless `ADD COLUMN ... GENERATED ALWAYS AS IDENTITY`
  breaking.
- **Snapshots carry a `formatVersion`, and a snapshot of any other version is
  refused rather than read.** Format 2 added index predicates and referential
  actions; a format 1 baseline lacks both, so reading it would report every
  partial index and every key as changed. Re-capture the baseline with
  `writeBaseline()` and review the diff before promoting it.
- **The runner is meant to be called once per run.** It writes to fixed paths
  from its properties and takes no lock, so concurrent calls sharing one output
  directory race on the report files. Each caller's returned report — and the
  gate — stays correct; only the files on disk can end up from the other run.
- **`SERIAL` reports the physical type `serial`, not `int4`.** The driver
  synthesizes that name from the `nextval` default, so migrating a column
  between the `SERIAL` and `INTEGER DEFAULT nextval(...)` spellings shows up as
  a `RISKY` physical-type change even though nothing about the column moved.
- **Widening and narrowing are not told apart.** `varchar(64) → varchar(8)` and
  `varchar(8) → varchar(64)` are both `RISKY`.
- **The contract protects readers as well as writers.** `nullable → NOT NULL`
  is `BREAKING` because writers that omit the column now fail; `NOT NULL →
  nullable` is `RISKY` because readers lose a guarantee they may rely on, even
  though nothing that used to be written is rejected.
- **Nothing is parsed.** No SQL, no migrations, no vendor dialects — two
  normalized structures in, a list of changes out.

Not in v1: a CLI, ODCS export, environment-vs-environment comparison, migration
execution, and rewriting `SchemaValidator` on top of this model.

## Why SchemaCrawler, and why generic JDBC retrieval

Turning JDBC metadata into a usable model across vendors is a solved problem,
and SchemaCrawler solves it. What is *not* solved anywhere we would want to
depend on is the product question this module exists for: whether a given change
breaks a consumer. TestForge owns the model, the diff, the policy and the
report; it borrows only the reading.

The inspector deliberately stays on **generic JDBC retrieval** rather than
letting SchemaCrawler match the connection to a known server type. That match
makes SchemaCrawler demand the vendor's own plugin on the classpath —
`schemacrawler-postgresql` for PostgreSQL, and so on — which would turn every
supported database into a new dependency for detail this bounded model does not
carry. Table types and identifier rules are read from the driver instead, so
PostgreSQL and H2 both work with the single `us.fatehi:schemacrawler` artifact.

That artifact is the plain Maven jar, which ships **without** bundled JDBC
drivers and is available under EPL-2.0 / GPL-3.0 / LGPL-3.0. The GPL-only terms
apply to SchemaCrawler's packaged distributions, which do bundle drivers — the
project's JDBC driver stays the project's own dependency. SchemaCrawler is
declared `implementation`, so its types never reach this module's public API and
consumers get it at runtime scope only.

`module-db` does not depend on this module: a project that only wants
`DbWaiter.awaitRow(...)` never sees SchemaCrawler.

## Verification

- `module-db-contract` unit tests cover the comparator, the policy, snapshot
  determinism, and the H2-backed inspector.
- `DbContractExampleTest` in `example-tests` runs the workflow offline.
- `PostgresDbContractIT` proves every classification against a real PostgreSQL
  through Testcontainers:
  `./gradlew :example-tests:containersTest --tests '*PostgresDbContractIT*'`
