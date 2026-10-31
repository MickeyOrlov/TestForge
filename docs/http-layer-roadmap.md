# HTTP layer: deep development plan

The API layer is what every adapted project's tests go through, so each
improvement here pays off in every test at once. Four steps, each shipped and
merged on its own.

| Step | What a test author gets | Where | Status |
|---|---|---|---|
| 1. Contract guard | every `ApiClient` response checked against the OpenAPI document during ordinary functional tests (`record` or `fail`) | `module-api-explorer` (`forge.contract-guard`) | shipped |
| 2. Failure diagnostics | the scenario's last requests and responses, redacted, in the run index when a test fails (`HttpDiagnosticsExtension`) | `module-http` | shipped |
| 3. Await a response | `poll until the response matches` through `Waiter`, no hand-written loops (`ResponsePoller`, `ResponseConditions`) | `module-http` | shipped |
| 4. Pagination | iterate every page (page/offset, cursor, `Link`) with one call | `module-http` | planned |

## Why step 1 lives in module-api-explorer

The response checker already exists in `module-api-explorer`, which depends on
`module-http`. Moving it into `module-http` would create a dependency cycle the
architecture guardrails forbid. The guard is a separate auto-configuration
(`forge.contract-guard.enabled`), independent of `forge.api-explorer.enabled`,
and plugs into `ApiClient` as an ordinary REST Assured `Filter` — no change to
`module-http` was needed.
