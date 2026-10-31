# Query checks

The data checks (`docs/db-checks-roadmap.md`) assert what an action left in the
database. These assert how it got there: how many statements it ran, whether
one ran once per row, whether a write had no `WHERE`, whether a failed action
left half its writes behind, and which statement was slow.

All five steps shipped (PRs #114–#118).

| Step | What | Status |
|---|---|---|
| 1 | **Query budget** — statements per action, per kind, and the N+1: one statement shape run once per row | shipped |
| 2 | **Unbounded statements** — an `UPDATE`/`DELETE` without `WHERE`, a `SELECT` of a whole table | shipped |
| 3 | **Atomicity** — an action that failed left nothing behind | shipped |
| 4 | **Slow statements** — which statement of a scenario took longer than allowed | shipped |
| 5 | **Review** — close the gaps a review finds | shipped |

## Step 1 — query budget

`forge.db.queries.enabled=true` wraps the datasources (as `log-sql` and
diagnostics do) and adds `DbQueries`. `queries.during(action)` captures every
statement any thread ran while the action ran — the application's own request
threads included when it runs in the test's JVM; `duringThisThread` narrows it
to the caller; `returning(supplier)` keeps the action's result. The capture
holds SQL text, batch size, timing, thread and outcome, never parameter values.

`expect().atMost(n)`, `atMost(n, kind)`, `none(kind)` and `noRepeated(times)`
— the N+1 check: statements are grouped by shape (string and number literals
and `IN (...)` lists folded), so `SELECT … WHERE order_id = ?` run fifty times
is one shape run fifty times. Failures list every shape with its count.

## Step 2 — unbounded statements

`noUnboundedWrites()` fails on an `UPDATE` or `DELETE` without `WHERE` and on
`TRUNCATE` — the missing tenant filter that a data check only sees afterwards
as "my row changed". `noUnfilteredReads(allowedTables...)` fails on a `SELECT`
from a table with no `WHERE`, `LIMIT`, `FETCH` or `TOP`, reference tables
aside (schema prefixes ignored when matching). String literals are blanked
before looking for the clauses. The checks read SQL text, not plans: a
whole-table subquery under a filtered outer query passes, and a `WHERE` that
filters nothing (`WHERE 1 = 1`) counts as a filter.

## Step 3 — atomicity

`DbSnapshot.expectRolledBack(action)` (and the same on `TableSnapshot`) runs an
action that must throw, then requires every watched row to be as it was. A
success fails with what it changed; a failure that left rows fails with them,
the action's exception attached as the cause — the lost `@Transactional`, the
two writes in two transactions. It returns the exception for further checks.
`assertUnchanged()` covers a rejection that does not throw, and
`assertUnchangedWithin(Duration)` a saga whose compensation undoes the write
later. (Built on the data snapshots rather than the SQL capture, so it is
listed here for the question it answers — what a failure left — not for how.)

## Step 4 — slow statements, swallowed failures

`CapturedQueries` keeps the driver's time per statement: `slowest(n)`,
`totalMillis()`, `expect().noneSlowerThan(limit)` (each offender with its time,
shape and thread) and `totalUnder(budget)` for the action's database time as a
whole. `failed()` / `noErrors()` catch a statement that failed inside an action
that itself succeeded — a duplicate key the code caught and answered 200 to, a
deadlock retried in silence. Times on a shared database measure the
environment too; the limits are meant for gross regressions.

## Step 5 — review

- **Comments and parentheses hid the kind.** A statement starting with
  `/* … */` (Hibernate's `use_sql_comments`) or `(` was `OTHER`, so it escaped
  every per-kind check. Leading comments and parentheses are skipped.
- **A CTE hid a write.** `WITH … UPDATE …` read as a `SELECT`, and the CTE's
  `WHERE` made the update look bounded: a mass update through a CTE passed
  `noUnboundedWrites()`. The kind comes from the statement after the CTEs, and
  a write is judged by its own top-level `WHERE` — which also fixes
  `UPDATE t SET a = (SELECT … WHERE …)` without a `WHERE` of its own.
- **An action that threw lost its statements.** `during` closed the window and
  rethrew; `open()` / `openThisThread()` return a window the test closes, its
  statements readable while open and after.
- **Threads held.** Each captured statement kept a reference to its `Thread`,
  in a public record; it keeps the thread id now.
