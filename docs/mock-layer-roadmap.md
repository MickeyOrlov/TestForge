# Mock layer roadmap

`module-mock` already isolates scenarios on a shared WireMock (scope markers),
asserts on a scenario's own traffic and explains unmatched requests. What
tests still lack: downstreams that change their answer over time, knowing
when a stub a test relied on was never used, and stubs that cannot lie about
the real service's contract.

| Step | What | Where | Status |
|---|---|---|---|
| 1 | **Sequences per scope** — the same request answered differently each time (`503, 503, 200`), isolated per scenario | `module-mock` | shipped |
| 2 | **Unused stubs** — a scoped stub nothing called means the scenario took another path; reported, optionally failing | `module-mock` | planned |
| 3 | **Stubs held to the contract** — a scoped stub's response checked against the downstream's OpenAPI document | `module-api-explorer` | planned |
| 4 | **Review** — close the gaps a review of the module finds | `module-mock` | planned |
