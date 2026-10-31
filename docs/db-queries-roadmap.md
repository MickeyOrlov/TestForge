# Query checks

The data checks (`docs/db-checks-roadmap.md`) assert what an action left in the
database. These assert how it got there: how many statements it ran, whether
one ran once per row, whether a write had no `WHERE`, whether a failed action
left half its writes behind, and which statement was slow.

| Step | What | Status |
|---|---|---|
| 1 | **Query budget** — statements per action, per kind, and the N+1: one statement shape run once per row | shipped |
| 2 | **Unbounded statements** — an `UPDATE`/`DELETE` without `WHERE`, a `SELECT` of a whole table | shipped |
| 3 | **Atomicity** — an action that failed left nothing behind | planned |
| 4 | **Slow statements** — which statement of a scenario took longer than allowed | planned |
| 5 | **Review** — close the gaps a review finds | planned |

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
