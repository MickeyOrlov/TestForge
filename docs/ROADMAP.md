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
| `module-db` | `DbWaiter`, repository polling, SQL logging, schema drift checks with documented limits. |
| `module-flow` | Deterministic state-machine setup with path logging and decorators. |
| `module-kafka` | Kafka buffer/probe/collector; contract validation composes outside the module. |
| `module-mock` | Scenario-scoped WireMock stubs for shared mock servers. |
| `module-reporting` | Lightweight resource usage monitor and optional Allure attachment helper. |
| `module-web` | Playwright-powered environment prewarm, best-effort by design. |

### Beta

These modules are implemented and covered by examples, but they are newer or
depend on opt-in runtime integrations.

| Module | Status note |
|---|---|
| `module-contract-monitor` | JUnit-friendly Kafka contract drift monitor with shape snapshots, baseline diffs, and reports. |
| `module-state` | Reusable state recipes over `module-flow`, bridged into `@Prepared`. |
| `module-web-playwright` | Playwright lifecycle, `Page` fixture, and failure artifacts. Browser-backed examples run outside the default build. |
| `module-mobile-appium` | Appium device matrix, lazy sessions, JUnit fixture extension, optional local node, and failure artifacts. Real devices are opt-in. |

### Experimental

No experimental production module is currently wired into the build. Ideas such
as API discovery or Gherkin fragments are documented as backlog candidates, not
as implemented features.

## Completed

- Multi-module Gradle template with Java 21 LTS toolchains and Spring Boot 3.5.x
  auto-configuration modules.
- Offline reference suite in `example-tests` using H2, embedded WireMock, and
  direct in-memory fixtures.
- CI definitions for GitHub Actions and GitLab CI.
- Docker runner image for warmed Gradle dependencies and Playwright browser
  assets.
- Core polling primitives: `Waiter` and `DbWaiter`; no `Thread.sleep` contract.
- Typed scenario context with JUnit cleanup support.
- Side-effect assertions through `StateSnapshot` and `StateDiff`.
- Scoped WireMock stubs for parallel tests against a shared mock server.
- Kafka message buffer, filters, probe API, and optional polling collector.
- JSON contract validation with JSON Schema support.
- CI-style contract monitor that validates Kafka payloads, stores redacted
  artifacts, and compares payload shapes against baselines.
- Prepared data pool SPI and `@Prepared` parameter injection.
- State recipes that compose reusable setup flows and prepared data.
- Flow runner with guardrails, path reporting, and decorators.
- Basic JVM resource diagnostics with optional Allure attachment helper.
- UI environment prewarm.
- Playwright page fixture and failure artifacts.
- Appium session fixture, device matrix, capability mapping, optional node
  lifecycle, and failure artifacts.
- Adaptation documentation, staging configuration template, and parallel test
  guidance.

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

### Unified Reporting Artifacts

Target module: `module-reporting`

- Introduce a shared artifact/report abstraction for test diagnostics.
- Keep Allure optional.
- Collect module outputs such as resource stats, SQL logs, flow paths, mock
  diagnostics, contract reports, Playwright screenshots, and Appium artifacts
  into predictable CI directories.

### Stronger Database Support

Target module: `module-db`

- Add named datasource support for multi-service test suites.
- Allow `DbWaiter` and `SchemaValidator` to target an explicit datasource.
- Expand schema drift checks beyond missing columns where it remains practical
  and dependency-light.

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
