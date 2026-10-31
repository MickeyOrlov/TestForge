# Deeper database checks

`module-db` let a test wait for a row (`DbWaiter`) and see what an action
changed (`DbState`): rows added, removed, changed, and in which columns. All of
it answers "did what I expected appear?". This plan deepens what a test can
assert about the database itself — the exact transition of a row, that nothing
else moved, that the data stays consistent — so a backend that writes the
right row *and* breaks something next to it fails the test.

| Step | What | Status |
|---|---|---|
| 1 | **Exact row expectations** — a row's column moved from → to, a version grew by one, a timestamp moved forward, the rest untouched | shipped |
| 2 | **Side-effect containment** — snapshot several tables, assert that only the expected ones and rows changed | shipped |
| 3 | **Data invariants** — orphans, duplicate business keys, values outside an allowed set, NULLs where logic forbids them | planned |
| 4 | **Cross-table consistency, with waiting** — every new order has its outbox row; totals add up | planned |
| 5 | **Only the test's own data** — the rows an action touched carry the test's run tag | planned |
| 6 | **Review** — close the gaps a review finds | planned |

## Step 1 — exact row expectations

`TableDiff.expect().row(keyValues...)` checks one row column by column:
`changed(column, from, to)`, `changedTo`, `increasedBy`, `movedForward`,
`unchanged(columns...)` and `onlyThese()` — no column moved that the checks did
not name. `addedRow(...).has(column, value)` checks a new row, `removedRow(...)`
a deleted one. Values compare by meaning across JDBC types (`42` against
`BIGINT` or `NUMERIC`, enums by name, `LocalDateTime` against `TIMESTAMP`).
Columns in `ignoring(...)` still never make a row count as changed, but their
moves are kept (`RowChange.ignored()`, `TableDiff.touched()`), so
`movedForward("updated_at")` works on them — and caught an `updated_at` that
went backwards.

## Step 2 — side-effect containment

`DbState.watch(TableQuery...)` takes several snapshots as one `DbSnapshot`;
its `DbDiff` names the tables that moved, and `expect()` combines per-table
expectations (`table(name, t -> ...)`, the full `TableDiff` vocabulary
including row checks) with `nothingElse()` — every watched table without
expectations must be untouched — or `onlyChanged(...)`. Failures list the
offending tables with their diffs. `watchSchema(excluded...)` watches every
table of the connection's schema, keyed by primary key from the JDBC metadata
and by all columns where there is none: the strongest statement, for a
database the test owns. Shared databases keep `watch` with `where` clauses.
