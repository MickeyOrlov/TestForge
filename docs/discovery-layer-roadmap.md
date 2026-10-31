# Discovery layer roadmap

`module-api-discovery` snapshots an OpenAPI document's endpoints and schema
shapes and diffs them against a baseline. A diff says *that* the API changed;
CI needs to know whether the change breaks clients, and the suite needs to
know which documented operations its tests never touch.

| Step | What | Where | Status |
|---|---|---|---|
| 1 | **Change impact** — every catalog and shape difference judged for clients; fail only on breaking changes | `module-api-discovery` | shipped |
| 2 | **Operation coverage** — documented operations no functional test called, from the contract guard's observations, in the run index | `module-api-explorer` | shipped |
| 3 | **Review** — close the gaps a review of the module finds | `module-api-discovery` | planned |
