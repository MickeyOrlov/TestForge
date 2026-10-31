# Database layer roadmap

`module-db` answers "did the row appear" (`DbWaiter`) and "do the mappings
still fit the schema" (`SchemaValidator`). A gray-box test also needs to say
what an action changed *exactly*, to see the SQL behind a failed test, and to
set up and remove its own rows without hand-written JDBC.

| Step | What | Where | Status |
|---|---|---|---|
| 1 | **Table snapshots** — rows before and after an action, diffed per column; expectations fail with the whole diff | `module-db` (`DbState`) | shipped |
| 2 | **SQL in failure diagnostics** — the last statements a failed test ran, with bound parameters, in the run index | `module-db` | shipped |
| 3 | **Rows for setup** — insert a row, get its key back, and have it removed after the test even when it fails | `module-db` | planned |
| 4 | **Review** — close the gaps a review of the module finds | `module-db` | planned |
