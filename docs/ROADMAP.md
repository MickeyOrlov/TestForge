# TestForge Roadmap

This document describes where TestForge is today and what kinds of changes fit
the project. It is intentionally conservative: the template should look boring
in the right places, compose proven tools, and stay easy to delete or adapt.
Architecture diagrams live in [architecture.md](architecture.md).

## Vision

TestForge is a JVM test automation template and accelerator, not a replacement
for the tools teams already trust.

- TestForge does not replace Spring Boot, JUnit, Playwright, Appium, Allure, or
  REST Assured.
- TestForge uses proven tools and provides an opinionated architecture around
  them: module boundaries, configuration conventions, CI-safe defaults,
  adaptation notes, and living examples.
- `core` must stay thin. A class belongs there only when more than one module
  needs it.
- Modules must remain deletable. Removing an unused module and its
  `settings.gradle` entry should not force unrelated refactoring.
- Default behavior must be CI-safe: no hidden external service calls, no
  surprise browser/device startup, no swallowed failures.
- The default build must stay offline-first. Heavy or environment-backed suites
  belong in explicit Gradle tasks, tags, profiles, or CI jobs.

## Current Status

Status reflects the current repository, not future intent.

### Stable

These modules have a narrow scope, examples, and are suitable as default
template building blocks.

| Module | Status note |
|---|---|
| `core` | `ScenarioContext`, `ScenarioContextExtension`, `Waiter`, `StateSnapshot`/`StateDiff`; `Waiter` timeouts name the last value and the last poll error. Plan: [core-layer-roadmap.md](core-layer-roadmap.md). |
| `module-contract` | JSON payload validation through `MessageContract` and `SchemaContract`; schemas drafted from real payloads; schema version changes classified for consumers. Plan: [contract-layer-roadmap.md](contract-layer-roadmap.md). |
| `module-data` | Unique values, template rendering, generators, `Factory`, `@Prepared` pool SPI, `Cleanup`. Plan: [data-layer-roadmap.md](data-layer-roadmap.md). |
| `module-db` | `DbWaiter` (repository or SQL value/count, parameters bound), repository polling, SQL logging, schema drift checks with documented limits, `DbState` table diffs (with exact per-row expectations) and setup rows, SQL in failure diagnostics. Plans: [db-layer-roadmap.md](db-layer-roadmap.md), [db-checks-roadmap.md](db-checks-roadmap.md), [db-queries-roadmap.md](db-queries-roadmap.md). |
| `module-flow` | Deterministic state-machine setup with timed paths, declared transitions (verify, Mermaid diagram), suite-wide transition coverage and decorators. Plan: [flow-layer-roadmap.md](flow-layer-roadmap.md). |
| `module-kafka` | Kafka buffer/probe/collector (group per run, assigned before tests start), publisher with scenario headers, exactly-one/none assertions, failure diagnostics; contract validation composes outside the module. Plan: [kafka-layer-roadmap.md](kafka-layer-roadmap.md). |
| `module-mock` | Scenario-scoped WireMock stubs for shared mock servers, per-scope response sequences, unused-stub detection; stubs checked against the downstream's document through `module-api-explorer`. Plan: [mock-layer-roadmap.md](mock-layer-roadmap.md). |
| `module-reporting` | Run-scoped artifact collection with one run per test JVM, deterministic manifest/summary index with a failed test's evidence gathered under the test, resource usage monitor, and optional Allure attachments. Plan: [reporting-layer-roadmap.md](reporting-layer-roadmap.md). |
| `module-web` | Playwright-powered environment prewarm, best-effort by design; what each page answered in the log and `prewarm.md`, optional stop when the environment is down. Plan: [web-layer-roadmap.md](web-layer-roadmap.md). |

### Beta

These modules are implemented and covered by examples, but they are newer or
depend on opt-in runtime integrations.

| Module | Status note |
|---|---|
| `module-contract-monitor` | JUnit-friendly Kafka contract drift monitor over a sample of messages, with merged shape snapshots, baseline diffs judged for consumers, and reports that never carry payload or header values. Plan: [monitor-layer-roadmap.md](monitor-layer-roadmap.md). |
| `module-api-discovery` | OpenAPI endpoint catalog and request/response schema shape snapshots for CI drift checks; every change judged for clients, optional fail-on-breaking-only gate. Plan: [discovery-layer-roadmap.md](discovery-layer-roadmap.md). |
| `module-db-contract` | Schema snapshot, bounded diff, and compatibility classification (`BREAKING`/`RISKY`/`NON_BREAKING`/`UNKNOWN`) with a CI gate. Reading is delegated to SchemaCrawler; the model, diff, policy and report belong to TestForge. Likely renames are pointed out. Plan: [db-contract-layer-roadmap.md](db-contract-layer-roadmap.md). |
| `module-http` | Preconfigured REST Assured specification: environment base URLs, scenario scope and request id correlation, redacted logging, opt-in retry. Authentication is left to project customizers. |
| `module-auth` | Credentials per role (static, OAuth2 client credentials/password, local HS256 JWT, API key, session-cookie login), cached with early refresh, `@AsUser` and reserved `anonymous` role, role shown in failure diagnostics, access matrix (operation × role) through `module-api-explorer`. |
| `module-state` | Reusable state recipes over `module-flow`, bridged into `@Prepared`; flows named per recipe, declared targets, all recipes verified without running. Plan: [state-layer-roadmap.md](state-layer-roadmap.md). |
| `module-web-playwright` | Playwright lifecycle, `Page` fixture, and failure artifacts. Browser-backed examples run outside the default build; a failing test also leaves `browser.md` (failed requests, console and page errors). Plan: [playwright-layer-roadmap.md](playwright-layer-roadmap.md). |
| `module-mobile-appium` | Appium device matrix, lazy sessions, JUnit fixture extension, optional local node, and failure artifacts (screenshot, page source, session summary with redacted capabilities, device log tail). Real devices are opt-in. Plan: [mobile-layer-roadmap.md](mobile-layer-roadmap.md). |

### Experimental

These modules are wired and tested, but their public API or supported schema
surface is still expected to evolve.

| Module | Status note |
|---|---|
| `module-api-codegen` | OpenAPI-first Java records and typed `ApiClient` skeletons, compiled right after generation so a document Java cannot take fails the run that wrote it. Writes build-owned sources and reports but does not provide a Gradle plugin, runtime probing, enum classes, or polymorphic model generation. Plan: [codegen-layer-roadmap.md](codegen-layer-roadmap.md). |
| `module-api-explorer` | Runs an OpenAPI document against a live environment and reports runtime contract drift per operation. V1 is stateless and safe-by-default: no request bodies, no value extraction between calls, no fuzzing. |
| `module-api-fuzz` | Thin adapter for the Schemathesis CLI engine. Handles configuration, safety policy, and result ingestion; findings grouped by check and operation in a `report.md` with reproduce commands. Plan: [fuzz-layer-roadmap.md](fuzz-layer-roadmap.md). |

## Completed

- Multi-module Gradle template with Java 21 LTS toolchains and Spring Boot 3.5.x
  auto-configuration modules.
- Independent Maven publications for all production modules, verified by a
  standalone external Gradle consumer of `module-http` and
  `module-api-discovery`.
- Offline reference suite in `example-tests` using H2, embedded WireMock, and
  direct in-memory fixtures.
- CI definitions for GitHub Actions and GitLab CI.
- Docker runner image for warmed Gradle dependencies and Playwright browser
  assets.
- Core polling primitives: `Waiter` and `DbWaiter`; no `Thread.sleep` contract.
- Typed scenario context with JUnit cleanup support.
- Side-effect assertions through `StateSnapshot` and `StateDiff`.
- Scoped WireMock stubs for parallel tests against a shared mock server.
- API client layer: environment-aware REST Assured specifications, automatic
  scope/correlation propagation, redacted HTTP logging, opt-in retry.
- Kafka message buffer, filters, probe API, and optional polling collector.
- JSON contract validation with JSON Schema support.
- Safe runtime API exploration: OpenAPI-driven calls against a live
  environment with per-operation observations and a runtime contract report.
- CI-style contract monitor that validates Kafka payloads, stores redacted
  artifacts, and compares payload shapes against baselines.
- OpenAPI discovery module that writes endpoint catalogs, request/response
  schema shape snapshots, and baseline diffs without runtime HTTP calls.
- OpenAPI code generation module that writes Java records and typed REST
  Assured client skeletons to a generated source directory without calling the
  target API or overwriting hand-written tests.
- Prepared data pool SPI and `@Prepared` parameter injection.
- State recipes that compose reusable setup flows and prepared data.
- Flow runner with guardrails, path reporting, and decorators.
- Role-based authentication for API tests: `module-auth` token providers,
  `@AsUser`, local JWT signing without an identity provider; API keys and
  session-cookie login; the role of every request in failure diagnostics; an
  access matrix that calls every operation as every role
  ([plan](auth-layer-roadmap.md)).
- HTTP layer: contract guard on every `ApiClient` response, failed-test HTTP
  diagnostics, `ResponsePoller`, `Pagination` ([plan](http-layer-roadmap.md)).
- Test data layer: `Cleanup`, `Factory`, data isolation tags, leftovers in the
  run index ([plan](data-layer-roadmap.md)).
- Database layer: table snapshots and per-column diffs, SQL of failed tests in
  the run index, setup rows removed after the test, `DbWaiter.awaitValue`
  ([plan](db-layer-roadmap.md)).
- Flow layer: timed transitions, declared transitions verified and drawn
  before running, transition coverage across the suite
  ([plan](flow-layer-roadmap.md)).
- Contract monitor: a sample of messages instead of the newest one, drift
  judged for consumers with a breaking-only gate ([plan](monitor-layer-roadmap.md)).
- Discovery layer: API changes judged for clients with a breaking-only gate,
  and operation coverage — documented operations no test called — from the
  contract guard ([plan](discovery-layer-roadmap.md)).
- Reporting layer: one complete index per test JVM however many Spring
  contexts, a failed test's evidence from every module under the test
  ([plan](reporting-layer-roadmap.md)).
- Contract layer: first schemas drafted from real payloads, schema version
  changes classified for consumers ([plan](contract-layer-roadmap.md)).
- State layer: recipes under their own flow name, declared targets with a
  clear error for a typo, every recipe verified up front
  ([plan](state-layer-roadmap.md)).
- Kafka layer: test event publishing, exactly-one and none-within-a-window
  assertions, a failed test's traffic in the run index, a collector that
  cannot silently miss messages ([plan](kafka-layer-roadmap.md)).
- Mock layer: response sequences per scope, unused stubs, stubs held to the
  downstream's contract ([plan](mock-layer-roadmap.md)).
- The run index's `summary.md` points at every report, including the ones a
  module keeps in its own output directory, and lists failing verdicts first.
- Every diagnostic-producing module publishes into the unified run index:
  runner reports (API discovery, exploration, fuzzing, contract monitor, DB
  contract) and UI/mobile failure artifacts, through `ArtifactSink`.
- Unified reporting artifacts: run-scoped artifact collection and layout (`ArtifactRunLayout`), deterministic `manifest.json` (`ArtifactManifestWriter`) and `summary.md` (`ArtifactSummaryWriter`) index, `ArtifactSink` core seam, resource monitor, and optional Allure attachments.
- UI environment prewarm.
- Playwright page fixture and failure artifacts.
- Appium session fixture, device matrix, capability mapping, optional node
  lifecycle, and failure artifacts.
- Adaptation documentation, staging configuration template, and parallel test
  guidance.
- Schema-aware API fuzzing adapter: safely configures and orchestrates the
  Schemathesis CLI without building a custom fuzz engine.
- Named datasource selection in `module-db`: `DataSourceRegistry`,
  `DbWaiter.on(name)`, and `SchemaValidator.forDataSource(name)` target an
  explicit database by bean name.
- Schema drift detection in `module-db`: `SchemaValidator` now covers missing
  columns, column type family drift, and nullability drift. Type comparison
  uses `ColumnTypeFamily` (nine broad families); nullability is
  one-directional by design.
- Database contract checks in `module-db-contract`: deterministic schema
  snapshots, a bounded snapshot-to-snapshot diff covering tables, columns,
  primary keys, foreign keys and indexes, and a compatibility policy that tells
  CI whether a change breaks consumers. Proven against real PostgreSQL.

## In Progress

No production-code implementation is currently marked as in progress in this
repository.

The active documentation layer is this roadmap plus the short backlog in
[`BACKLOG.md`](../BACKLOG.md). New implementation work should move from backlog to an
issue or branch before being described here as in progress.

## Planned

The planned work below follows the existing philosophy: compose established
tools, keep modules independently removable, and keep the default build
offline-first.

### Stronger Database Support

Target module: `module-db`

- Delivered: `SchemaValidator` covers column type family, nullability, index
  and foreign-key drift against entity mappings, inheritance hierarchies and
  pluggable naming strategies, reflectively and without ORM internals.
  `module-db-contract` compares two database snapshots.
- Delivered ([plan](db-checks-roadmap.md)): row-level diffs, side-effect
  containment (`watch`, `watchSchema`, `nothingElse`), rollback checks, data
  invariants with waiting, and "only the test's own data"; and
  ([plan](db-queries-roadmap.md)) query capture with budgets, N+1, unbounded
  writes, slow and failed statements. Both run on H2 and, in
  `containersTest`, on PostgreSQL.
- Open: whether `module-db` and `module-db-contract` should share schema
  internals, once `module-db-contract` has proven itself.

### Better Mock Failure Diagnostics

Target module: `module-mock`

- Delivered: scope matching by body JSON path, header, query parameter or
  cookie (`ScopeMarker`), scoped journal assertions (`requests`, `verify`),
  readable mismatch diagnostics, and scoped stubs plus unmatched requests as
  failure artifacts.
- Delivered after that ([plan](mock-layer-roadmap.md)): response sequences
  per scope for retry tests, unused scoped stubs (reported, optionally
  failing), and the stub contract check against the downstream's OpenAPI
  document.
- Remaining: none planned; expand only when an adaptation needs it.

### Messaging Abstraction

Target modules: `module-kafka`, possible future `module-messaging`

- In progress ([plan](messaging-layer-roadmap.md)): RabbitMQ as the second
  adapter. Shipped: the broker-neutral probe, buffer and criteria in `core`
  (`io.testforge.core.messaging`), `module-kafka` rebuilt on them without an
  API change, and `module-rabbitmq` (collector on a queue of its own,
  probe, confirmed publisher), and one failed-test traffic report for both
  (`MessageTrafficReport`, `RabbitDiagnosticsExtension`), and a review on a
  real broker. Done; a third broker adds an adapter, not a new layer.
- Keep Kafka as the first concrete implementation.
- Preserve separation between message collection and contract validation.

### Prepared Data Pool Refill

Target module: `module-data`

- Delivered: `forge.data.pool.preload` with optional background refill,
  per-variant statistics (`pool.stats()`), and `pool-stats.json` in the run
  index. Variants are tag sets; exhaustion is reported once per drain.

### Product Quality Assessment And Security

Target modules: `module-quality`, `module-security` (planned)

- Spec: [quality-security-spec.md](quality-security-spec.md). Part A first.
- Shipped (A1): `module-quality` reads a finished run's JUnit XML and run
  index and scores functional, stability and hygiene dimensions with printed
  formulas; `quality.md` / `quality.json`; a gate that fails closed.
- Shipped (A2): `quality-baseline.json`, new / accepted / known / resolved
  issues per dimension; the gate fails only on new ones.
- Shipped (A3): contracts, data, robustness, authorization and efficiency
  dimensions from the existing modules' reports; `module-api-fuzz` writes a
  machine-readable `findings.json` for it.
- Shipped (A4): the security dimension from `module-security`'s
  `security-findings.json`, and a review of Part A against real reports.
  Part A is complete; Part B (`module-security`) is in progress separately.

### Gherkin Steps

Target module: `module-gherkin` (optional)

- In progress ([plan](gherkin-layer-roadmap.md)): Cucumber steps over the
  modules for teams that write feature files. Shipped: scenario lifecycle
  (scope and correlation id per scenario), `${name}` variables, HTTP steps.
  Shipped too: Kafka and RabbitMQ steps (publish, await, exactly one, none)
  and run-unique variables; database steps (row exists / absent / changed
  since watched) and scoped mock steps. Next: review.

### API Bootstrap Path

Target modules: `module-api-discovery`, `module-api-codegen`,
`module-api-explorer`, `module-api-fuzz`

- Safe runtime exploration shipped in `module-api-explorer` v1: GET, HEAD and
  OPTIONS by default, mutation methods behind two explicit keys, redacted
  observations, runtime contract report. Done.
- Delivered: declarative value extraction, so an id returned by one operation
  satisfies another's parameter (`forge.api-explorer.extract`), and
  producer/consumer inference that proposes those rules in the report, and
  write chains (body templates, `DELETE` last) behind both write keys, and
  replay of a recorded run without the environment
  ([design](api-explorer-stateful-design.md)).
- Add stateful request sequences and reproducible replay on top of that
  inference, not before it.
- Consider a Gradle bootstrap plugin only after discovery, code generation,
  exploration, and fuzzing remain useful as independent removable modules.

## Explicitly Out Of Scope

TestForge is not trying to create or replace:

- replacement for Spring;
- replacement for JUnit;
- replacement for Playwright;
- replacement for Appium;
- replacement for Allure;
- replacement for REST Assured;
- own browser engine;
- own assertion library;
- own HTTP client.

It should remain a template that arranges these tools into a maintainable test
automation architecture.
