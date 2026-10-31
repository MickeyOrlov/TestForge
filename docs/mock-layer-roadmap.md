# Mock layer roadmap

`module-mock` already isolates scenarios on a shared WireMock (scope markers),
asserts on a scenario's own traffic and explains unmatched requests. What
tests still lack: downstreams that change their answer over time, knowing
when a stub a test relied on was never used, and stubs that cannot lie about
the real service's contract.

| Step | What | Where | Status |
|---|---|---|---|
| 1 | **Sequences per scope** — the same request answered differently each time (`503, 503, 200`), isolated per scenario | `module-mock` | shipped |
| 2 | **Unused stubs** — a scoped stub nothing called means the scenario took another path; reported, optionally failing | `module-mock` | shipped |
| 3 | **Stubs held to the contract** — a scoped stub's response checked against the downstream's OpenAPI document | `module-api-explorer` | shipped |
| 4 | **Review** — close the gaps a review of the module finds | `module-mock` | shipped |

All four steps shipped (PRs #59–#62). The review checked artifact names
(already sanitized by the run layout) and found two small holes: a blank
scope id built a matcher on nothing — it would have matched traffic carrying
no scope at all — and a second `close()` overwrote the scope's artifact with
an empty one. Both are closed. Known limit: a long suite on one shared
WireMock grows its request journal; unused-stub and unmatched-request
lookups get slower with it, so reset the journal between suite runs.
