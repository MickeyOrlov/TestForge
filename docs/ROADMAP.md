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
| `core` | `ScenarioContext`, `ScenarioContextExtension`, `Waiter`, `StateSnapshot`/`StateDiff`. |
| `module-contract` | JSON payload validation through `MessageContract` and `SchemaContract`. |
| `module-data` | Unique values, template rendering, generators, `@Prepared` pool SPI. |
| `module-db` | `DbWaiter`, repository polling, SQL logging, schema drift checks (missing columns, column type families, nullability) with documented limits. |
| `module-flow` | Deterministic state-machine setup with path logging and decorators. |
| `module-kafka` | Kafka buffer/probe/collector; contract validation composes outside the module. |
| `module-mock` | Scenario-scoped WireMock stubs for shared mock servers. |
| `module-reporting` | Run-scoped artifact collection, deterministic manifest/summary index, resource usage monitor, and optional Allure attachments. |
| `module-web` | Playwright-powered environment prewarm, best-effort by design. |

### Beta

These modules are implemented and covered by examples, but they are newer or
depend on opt-in runtime integrations.

| Module | Status note |
|---|---|
| `module-contract-monitor` | JUnit-friendly Kafka contract drift monitor with shape snapshots, baseline diffs, and reports. |
| `module-api-discovery` | OpenAPI endpoint catalog and request/response schema shape snapshots for CI drift checks. |
| `module-db-contract` | Schema snapshot, bounded diff, and compatibility classification (`BREAKING`/`RISKY`/`NON_BREAKING`/`UNKNOWN`) with a CI gate. Reading is delegated to SchemaCrawler; the model, diff, policy and report belong to TestForge. |
| `module-http` | Preconfigured REST Assured specification: environment base URLs, scenario scope and request id correlation, redacted logging, opt-in retry. Authentication is left to project customizers. |
| `module-state` | Reusable state recipes over `module-flow`, bridged into `@Prepared`. |
| `module-web-playwright` | Playwright lifecycle, `Page` fixture, and failure artifacts. Browser-backed examples run outside the default build. |
| `module-mobile-appium` | Appium device matrix, lazy sessions, JUnit fixture extension, optional local node, and failure artifacts. Real devices are opt-in. |

### Experimental

These modules are wired and tested, but their public API or supported schema
surface is still expected to evolve.

| Module | Status note |
|---|---|
| `module-api-codegen` | OpenAPI-first Java records and typed `ApiClient` skeletons. V1 writes build-owned sources and reports but does not provide a Gradle plugin, runtime probing, enum classes, or polymorphic model generation. |
| `module-api-explorer` | Runs an OpenAPI document against a live environment and reports runtime contract drift per operation. V1 is stateless and safe-by-default: no request bodies, no value extraction between calls, no fuzzing. |
| `module-api-fuzz` | Thin adapter for the Schemathesis CLI engine. Handles configuration, safety policy, and result ingestion. |

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

- Column type family drift and nullability drift are delivered. Index and
  foreign-key comparison now exists in `module-db-contract`, but between two
  database snapshots, not between JPA mappings and the database. Remaining for
  `SchemaValidator`: index and foreign-key drift against entity mappings where
  practical, and resolving inheritance hierarchies and custom naming strategies
  without pulling the module into heavy ORM internals. Whether the two modules
  should share internals is a question for after `module-db-contract` has
  proven itself.

### Better Mock Failure Diagnostics

Target module: `module-mock`

- Support scope matching beyond request-body JSON paths where useful
  (headers, query parameters, cookies).
- Provide readable diagnostics for unmatched scoped requests.
- Emit scoped stubs and request journal artifacts on failure.

### Messaging Abstraction

Target modules: `module-kafka`, possible future `module-messaging`

- Extract broker-neutral probe/buffer/filter concepts only if a second broker
  adapter is actually added.
- Keep Kafka as the first concrete implementation.
- Preserve separation between message collection and contract validation.

### Prepared Data Pool Refill

Target module: `module-data`

- Add optional preload/refill configuration for expensive prepared objects.
- Publish pool metrics through `PoolEventListener`.
- Report cold misses and exhausted variants in CI artifacts.

### API Bootstrap Path

Target modules: `module-api-discovery`, `module-api-codegen`,
`module-api-explorer`, `module-api-fuzz`

- Safe runtime exploration shipped in `module-api-explorer` v1: GET, HEAD and
  OPTIONS by default, mutation methods behind two explicit keys, redacted
  observations, runtime contract report. Done.
- Extend the observation model into value extraction and producer/consumer
  inference, so an id returned by one operation can satisfy another's
  parameter. The seam exists (`ValueSource`, `ApiObservation`); the inference
  does not.
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
