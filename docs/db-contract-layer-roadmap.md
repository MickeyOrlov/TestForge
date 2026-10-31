# DB contract roadmap

`module-db-contract` already judges every schema change for consumers and
gates CI on it; earlier reviews hardened the inspector, the snapshot format
and the fail-closed gate. What a person reading a red report still had to
work out alone: whether a "removed" column was really renamed, and whether
anything in this project's tests touches the object that moved.

All three steps shipped (PRs #93–#95).

| Step | What | Where | Status |
|---|---|---|---|
| 1 | **Possibly renamed** — a removed and an added object with an identical shape paired in the report and the gate message; verdicts unchanged | `module-db-contract` | shipped |
| 2 | **Who it hits** — changes on tables and columns the project's JPA entities map, named with the entity | `module-db-contract`, `module-db` | shipped |
| 3 | **Review** — close the gaps a review of the module finds | `module-db-contract` | shipped |

## Step 1 — possibly renamed

Objects are matched by name, so a rename reads as a `BREAKING` removal plus an
addition. That stays — a consumer selecting the old name fails either way —
but `DbRenameCandidates` pairs a removed and an added object in the same place
whose shape is identical (column: type, nullability, default; index: columns,
uniqueness, predicate; foreign key: columns, target, actions; table: every
column), and only when the pairing is unambiguous. The pairs appear under
"Possibly renamed" in `report.md`, in the failure message and on
`DbContractReport.renameCandidates()`.

## Step 2 — who it hits

`module-db` exposes `SchemaValidator.mapping(entity)` — the table and columns
an entity maps, resolved by the same rules as the drift checks, reading no
database. `module-db-contract` turns the persistence units' entities into a
`DbSchemaUsage` (lazily, on the first check) and attaches `usedBy` to every
assessment: a column change reaches the entities mapping that column, any
other change the entities mapping the table. `report.md` shows a "Used by"
column; the gate message names them. A failing lookup is logged and ignored —
usage informs the reader and never moves a verdict.

## Step 3 — review

The module fails closed when disabled or without a baseline. Two more ways to
be "gated" while gating nothing were still open:

- **An empty baseline.** `writeBaseline()` recorded whatever it found, and a
  wrong `schema` or an `include-tables` pattern matching nothing yields no
  tables. Under that baseline every table reads as added — non-breaking — and
  the gate can never fail. `writeBaseline()` now refuses an empty schema,
  naming the schema and patterns, and `assertCompatible()` fails closed on an
  empty baseline that already exists.
- **A baseline of another schema.** Pointing the check at a different schema
  than the baseline was captured for diffed into every table removed and
  added. It is refused as the configuration error it is (names compared
  ignoring case, as vendors fold them).
