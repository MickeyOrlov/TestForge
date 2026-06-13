# TestForge Backlog

This backlog tracks the next useful expansions after the current v1 template
work. It is intentionally product-neutral: no client, employer, or domain
specific names belong here.

## Priority 1: unified reporting artifacts

**Module:** `module-reporting`

Goal: make CI output easy to inspect after a failed run.

- Add a shared `TestArtifactCollector` / `RunReport` abstraction.
- Collect JSON/Markdown artifacts from modules into one predictable directory.
- Add optional Allure attachments without making Allure a hard dependency.
- Include resource usage, SQL logs, flow paths, mock diagnostics, Kafka/contract
  reports, Playwright screenshots, and Appium screenshots/page source.
- Keep diagnostics best-effort: reporting must not hide the original failure.

## Priority 2: stronger database module

**Module:** `module-db`

Goal: make gray-box DB checks comfortable for multi-service systems.

- Add a named datasource registry for projects with several service databases.
- Support `DbWaiter` and `SchemaValidator` against an explicit datasource.
- Expand schema drift checks beyond missing columns:
  - column type drift;
  - nullable drift;
  - indexes and foreign keys where practical.
- Decide how to support inheritance and custom naming strategies without
  pulling the module into heavy ORM internals.

## Priority 3: richer shared mock diagnostics

**Module:** `module-mock`

Goal: make scoped WireMock failures explain themselves.

- Support scope matching by request body JSON path, header, query param, and
  cookie.
- Add request journal assertions for scoped traffic.
- Dump scoped stubs and unmatched requests as failure artifacts.
- Add readable mismatch diagnostics: why a scoped stub did not match the
  incoming request.

## Priority 4: messaging abstraction beyond Kafka

**Modules:** `module-kafka`, possible future `module-messaging`

Goal: keep Kafka support, but make the probe/buffer/filter model reusable for
other brokers.

- Extract broker-neutral concepts: message envelope, filter, buffer, probe.
- Keep Kafka as the first concrete adapter.
- Add extension points for RabbitMQ, JMS, SQS, or other queue providers.
- Preserve the current rule: message collection and contract validation remain
  separate modules.

## Priority 5: prepared data pool refill

**Module:** `module-data`

Goal: make expensive prepared objects more reliable in long CI runs.

- Add optional background refill based on pool thresholds.
- Add YAML configuration for preload variants and counts.
- Publish pool metrics through `PoolEventListener`.
- Report cold misses and exhausted variants in CI artifacts.
- Keep prepared objects one-use only; do not add release/reuse semantics.

## Future modules

These are useful, but should stay behind the core CI/staging improvements.

- `module-gherkin`: reusable Cucumber/Gherkin fragments for organizations that
  already standardize on feature files.
- `module-api-discovery` / `module-api-scaffold`: discover endpoints from owned
  frontend bundles, save an endpoint catalog, and generate reviewable
  RestAssured/JUnit smoke-test skeletons.

## Keep Stable For Now

These modules are currently focused enough. Expand only when a real adaptation
requires it.

- `core`: keep thin; shared classes only when at least two modules need them.
- `module-contract`: keep the JSON Schema facade; do not grow a homemade DSL.
- `module-flow`: keep state-machine setup small and deterministic.
- `module-web`: keep prewarm focused; browser testing belongs in
  `module-web-playwright`.
