# Contract layer roadmap

`module-contract` validates payload shape with a frozen field DSL or a full
JSON Schema. Adoption stalls at the first schema, and a schema once written
changes without anyone noticing what that does to consumers.

| Step | What | Where | Status |
|---|---|---|---|
| 1 | **Draft from samples** — a first JSON Schema from real payloads, shape only, to review and commit | `module-contract` | shipped |
| 2 | **Schema change review** — two versions of a contract compared, each change classified for consumers (breaking, risky, safe) | `module-contract` | planned |
| 3 | **Review** — close the gaps a review of the module finds | `module-contract` | planned |
