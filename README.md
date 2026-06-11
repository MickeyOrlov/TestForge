# TestForge

![License](https://img.shields.io/badge/license-Apache--2.0-blue)
![Java](https://img.shields.io/badge/Java-26-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F)
<!-- after publishing, add the live CI badge:
![CI](https://github.com/OWNER/REPO/actions/workflows/build.yml/badge.svg) -->

**The first AI-native Test Automation Template for JVM.**

A template test framework for JVM backend ecosystems (Spring services, REST,
PostgreSQL, Kafka). Stack: Java 26, Spring Boot 4.x, Gradle 9.x, JUnit 5. Clone it, rename it, delete what you don't need — it is a
**template repository**, not a published library. The goal is to start a new
team's test automation from a skeleton that already encodes hard-won
decisions, instead of from an empty directory.

### Why TestForge?

1. **AI-Native**: Designed from the ground up to be adapted and extended by AI coding agents (Claude, Cursor, Gemini). See [AGENTS.md](AGENTS.md).
2. **Modern Concurrency**: Optimized for Virtual Threads (Java 26) with polling-based waits.
3. **Modular**: Deletable modules, no "heavy" dependencies by default.
4. **Offline-First**: Embedded Kafka/DB/Mocks in the reference suite for fast local feedback.

## Architecture

A deliberately thin core plus optional modules. Modules plug in through Spring
Boot auto-configuration: putting one on the classpath is all it takes.

| Module | What it gives you |
|---|---|
| [core](core) | Typed thread-local `ScenarioContext`, `Waiter` (polling instead of sleeps), config conventions |
| [module-contract](module-contract) | Lightweight JSON contract validation for API/queue/file payloads, useful for schema-drift checks |
| [module-data](module-data) | Per-run unique value registry and `%{variable}%` template rendering for data-heavy tests |
| [module-db](module-db) | `DbWaiter` for rows written asynchronously, SQL logging of every test query, `SchemaValidator` against schema drift |
| [module-flow](module-flow) | Tiny state-machine runner for long business flows, with path logging and cycle guardrails |
| [module-kafka](module-kafka) | Kafka message probe: bounded buffer, newest-first search, JSON-path filters, contract validation |
| [module-mock](module-mock) | `ScopedMockClient` — scenario-scoped stubs on a **shared** WireMock, safe for parallel runs |
| [module-reporting](module-reporting) | Resource usage monitor for JVM memory/CPU diagnostics in CI artifacts |
| [module-web](module-web) | `PrewarmRunner` — visits key pages once per suite so UI tests never start against a cold environment |
| [module-web-playwright](module-web-playwright) | *(Coming soon)* Full-blown UI automation with Playwright |
| [module-mobile-appium](module-mobile-appium) | *(Coming soon)* Cross-platform mobile automation (Appium) |
| [example-tests](example-tests) | Self-contained reference suite (WireMock + H2), runs offline |

## Quick start

```bash
./gradlew build          # compiles everything and runs the example suite
./gradlew :example-tests:test
```

The example suite needs no external services: it spins up an embedded
WireMock as the "system under test" and an in-memory H2 as the "service
database".

## Docker and CI

The repository has no long-running application container. The Docker image is a
CI runner image: Java 26, warmed Gradle dependencies, and Chromium for
`module-web` prewarm.

```bash
docker build -t testforge-runner .
docker run --rm -v "$PWD:/workspace" testforge-runner
docker compose run --rm testforge
```

CI follows the template definition of done:

- GitHub Actions runs `./gradlew build` on pushes and pull requests, uploads
  test reports, and can validate the Docker runner image on `main` or manually.
- GitLab CI runs the same build job for pushes and merge requests, has manual
  environment jobs (`-DtestEnv=<name>`), and includes a manual job that
  publishes the warmed runner image to the project registry.

## Configuration conventions

Everything lives under the `forge.*` prefix, one Spring profile per test
environment:

```yaml
# application-staging.yml
forge:
  wait:
    timeout: 30s
    poll-interval: 500ms
  mock:
    base-url: http://wiremock.staging.example.test:8080
    scope-json-path: "$.metadata.test_scope"
  db:
    log-sql: true
    repository-polling:
      enabled: false
  contract:
    fail-fast: false
    max-violations: 100
  data:
    max-template-depth: 10
  flow:
    timeout: 60s
    max-transitions: 100
    max-visits-per-state: 5
  kafka:
    enabled: false
    topics: []
    poll-timeout: 500ms
    max-messages-per-topic: 1000
  reporting:
    resource-monitor:
      enabled: false
      period: 2s
  prewarm:
    enabled: true
    urls:
      - https://staging.example.test/
```

Select an environment with `-DtestEnv=staging`; Gradle forwards it into forked
test JVMs as `spring.profiles.active`.

## Design rules

1. **The core stays thin.** If a class is useful to only one module, it lives
   in that module. The core earns a class only when two modules need it.
2. **No sleeps.** Anything asynchronous goes through `Waiter`/`DbWaiter`.
3. **Modules must be deletable.** Removing a module directory and its
   `settings.gradle` line must not break the rest of the build.
4. **Mock isolation comes from matchers, not from luck.** Scoped stubs match
   on a scenario id inside the request body; priority only decides who wins
   when a scoped stub and a default both match.
5. **Failures are loud.** No `ignoreFailures`, no swallowed assertion errors.
   The only deliberate exception: prewarm failure logs a warning and lets the
   suite run — a cold environment makes tests slower, not wrong.

## TestForge + Testcontainers + Pact

These are layers, not competitors. TestForge owns the test-side toolkit
(waits, scoped mocks, DB gray-box, flow setup, drift checks); Testcontainers
owns disposable infrastructure (broker/DB in CI — see
`PostgresSchemaValidationIT`); Pact / Spring Cloud Contract owns cross-team
provider/consumer contracts. `module-contract` deliberately stays a cheap
QA-side shape-drift check on real staging traffic — when two teams need a
verified API contract, reach for Pact. The full comparison matrix lives in
[docs/production-v1-gap-analysis.md](docs/production-v1-gap-analysis.md).

## Roadmap

Master plan (archive ideas, production v1 gaps, future modules):
[docs/ROADMAP.md](docs/ROADMAP.md). Production staging checklist:
[docs/production-v1-gap-analysis.md](docs/production-v1-gap-analysis.md).

Near-term modules worth adding as the need appears:

- background refill job for the prepared-object pool (`PoolEventListener`
  already provides the hooks)
- Allure integration (report steps via `FlowStepDecorator`, attachments for
  SQL log, scoped-stub diffs, prewarm timings)
- `module-gherkin` for Cucumber organizations: reusable scenario fragments
  (`@fragment`-tagged scenarios as callable macros, dependency graph with
  nested-fragment inlining and cycle detection)
- multi-datasource routing in module-db: named DataSources per service,
  `DbWaiter`/`SchemaValidator` with an explicit source
- messaging generalization beyond Kafka: same buffer/filter/probe API,
  pluggable collectors (Kafka, RabbitMQ)

## License

Apache-2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
