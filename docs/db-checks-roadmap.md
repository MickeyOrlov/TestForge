# Deeper database checks

`module-db` let a test wait for a row (`DbWaiter`) and see what an action
changed (`DbState`): rows added, removed, changed, and in which columns. All of
it answers "did what I expected appear?". This plan deepens what a test can
assert about the database itself — the exact transition of a row, that nothing
else moved, that the data stays consistent — so a backend that writes the
right row *and* breaks something next to it fails the test.

All six steps shipped (PRs #108–#113).

| Step | What | Status |
|---|---|---|
| 1 | **Exact row expectations** — a row's column moved from → to, a version grew by one, a timestamp moved forward, the rest untouched | shipped |
| 2 | **Side-effect containment** — snapshot several tables, assert that only the expected ones and rows changed | shipped |
| 3 | **Data invariants** — orphans, duplicate business keys, values outside an allowed set, NULLs where logic forbids them | shipped |
| 4 | **Cross-table consistency, with waiting** — every new order has its outbox row; totals add up | shipped |
| 5 | **Only the test's own data** — the rows an action touched carry the test's run tag | shipped |
| 6 | **Review** — close the gaps a review finds | shipped |

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

## Step 3 — data invariants

`DbState.invariants()` collects rules and runs them in one connection:
`noOrphans(child.column, parent.column)` (`NOT EXISTS`, NULLs aside),
`unique(table, columns...)` (`GROUP BY … HAVING COUNT(*) > 1`),
`allowedValues(table, column, values...)` (enums by name),
`notNull(table, columns...)` and `holds(table, condition)` for anything else.
`within(table, condition, params...)` scopes every rule reading a table to the
test's own data. `verify()` reports, `assertHolds()` fails with every broken
rule, its count and up to five rows. Identifiers are validated; conditions are
the test's own SQL, as in `where(...)`.

## Step 4 — cross-table consistency, with waiting

Three more rules: `everyRowHas(column, matchingColumn)` — each order has its
outbox event, the orphan check read the other way round;
`totalMatches(total, key, part, partKey)` — a stored total equals the sum of
its parts, zero when there are none; `countMatches(count, key, childKey)`.
`assertHoldsWithin(Duration)` runs the rules on the core `Waiter` until they
hold, for what the system completes asynchronously (the relay, a projection,
a recalculation), and fails at the deadline with the last report — the rules
still broken, not a bare timeout.

## Step 5 — only the test's own data

A table watched whole, then `expect().onlyOwnData(tagColumn)`: every added,
changed (ignored-column moves included) and removed row must carry a tag from
`DataIsolation`. Rows tagged by other tests are tolerated, since parallel tests
write concurrently; a row no test owns fails — the action reached real data on
the shared environment. `onlyThisTestsData` is the strict form for runs where
nothing else writes the table. `RowChange` now keeps the row's current values,
so the tag column is checked even when it did not change.

## Step 6 — review

- **Reserved-word tables.** `watchSchema()` built `SELECT * FROM ORDER` from
  the metadata and failed on any table called `order` or `user`. Discovered
  tables are quoted exactly as the database reports them
  (`TableQuery.discovered`), which is also the case they are stored in.
- **A big reference table.** Over 10 000 rows, `watchSchema()` told the user
  to raise `maxRows` — which it does not take. It now says to exclude the
  table, by name.
- **Rules that cannot run.** `assertHoldsWithin` treated a SQL error (a
  misspelled column in `holds(...)`) as "not yet" and waited out the whole
  deadline; it now fails at once with the error.
