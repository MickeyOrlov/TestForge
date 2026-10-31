# DB contract roadmap

`module-db-contract` already judges every schema change for consumers and
gates CI on it; earlier reviews hardened the inspector, the snapshot format
and the fail-closed gate. What a person reading a red report still had to
work out alone: whether a "removed" column was really renamed, and whether
anything in this project's tests touches the object that moved.

| Step | What | Where | Status |
|---|---|---|---|
| 1 | **Possibly renamed** — a removed and an added object with an identical shape paired in the report and the gate message; verdicts unchanged | `module-db-contract` | shipped |
| 2 | **Who it hits** — changes on tables and columns the project's JPA entities map, named with the entity and field | `module-db-contract`, `module-db` | planned |
| 3 | **Review** — close the gaps a review of the module finds | `module-db-contract` | planned |

## Step 1 — possibly renamed

Objects are matched by name, so a rename reads as a `BREAKING` removal plus an
addition. That stays — a consumer selecting the old name fails either way —
but `DbRenameCandidates` pairs a removed and an added object in the same place
whose shape is identical (column: type, nullability, default; index: columns,
uniqueness, predicate; foreign key: columns, target, actions; table: every
column), and only when the pairing is unambiguous. The pairs appear under
"Possibly renamed" in `report.md`, in the failure message and on
`DbContractReport.renameCandidates()`.
