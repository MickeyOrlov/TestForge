# module-load: working on this module

Read the root [AGENTS.md](../AGENTS.md) first. Usage lives in [README.md](README.md).

**Scope:** thin local adapter over k6 OSS: opt-in configuration, external process lifecycle,
native summary ingestion, CI verdict and run-scoped evidence. No load engine or scenario DSL.

**May depend on:** `core` only. No dependency on module-http, api-fuzz, reporting or quality.

## Invariants

- Creating a Spring context never runs k6. Disabled direct calls fail closed.
- k6 owns scenarios, users, pacing, metrics and thresholds. Never wrap REST Assured in a load loop.
- Every run requires a trusted local script, the configured k6 version, completed iterations and
  a valid summary. Default project validity policy also requires HTTP requests/target and thresholds;
  those checks can be explicitly relaxed through LoadPolicyProperties for other native scenarios.
- Only exit 0/99 can be classified from summary; timeout/cancel/other exit cannot pass.
- Dropped iterations invalidate a run by default; a project may disable that validity rule for
  exploratory stress tests. Failed native thresholds always fail. Raw output stays bounded/private.
- Per-invocation UUID directories prevent stale results and concurrent writers from colliding.
- Native JSON uses the documented handleSummary contract and explicit legacy-format CLI flag.
  The raw summary is deleted; only whitelisted numeric aggregates go into report.json.
- Environment and target credentials never go into arguments or evidence. HTML is explicit opt-in:
  native dashboards can include script-defined names/tags. Scripts are trusted code, not sandboxed.
- Default process environment does not inherit CI secrets or ambient K6_* options. An isolated
  empty k6 config overrides the user's config. Credentials reach the script only by explicit mapping.
- Remote write is opt-in through typed PrometheusProperties; credentials remain in the environment.
  Run-specific labels are explicit opt-in; defaults reuse series. Remote write excludes URL/name and
  free-text system tags; custom script tags still require review. Never average worker p95 gauges.
  Optional metrics delivery does not change native threshold verdicts. observabilityTest checks delivery.
- Native k6 runs require a separate job/profile. Default build uses stubs plus local child-JVM tests.

## Tests

`LoadRunnerTest`, `LoadConfigurationTest`, `LocalLoadProcessTest`; offline `LoadExampleTest` in
example-tests. `PrometheusOutputTest` covers remote-write wiring and redaction. `LoadObservabilityTest`
(tag `load-observability`) requires the explicit infra/load stack and verifies ingestion/Grafana queries.
`RealK6Test` (tag `k6`) uses exactly three requests per run to an owned loopback server.

Before finishing: `./gradlew :module-load:test :architecture-tests:test`, then `./gradlew build`.
For native compatibility: `./gradlew :module-load:k6Test -Pk6Executable=/absolute/path/to/k6`.
Never run a load scenario against a live/shared target without an explicitly selected environment.

API research composition lives in example-tests/gradle/load.gradle and tools/api-load. It exchanges
files and calls the upstream generator. Never move producer dependencies or OpenAPI generation into
this module; a prepared native bundle must remain runnable without the research modules.
