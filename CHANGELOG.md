# Changelog

All notable changes to this template are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); the project uses
semantic versioning for its git tags.

## [Unreleased]

### Fixed
- `module-mobile-appium`: failure-artifact capture no longer aborts the page
  source when the screenshot fails — each artifact is captured independently
  and errors are surfaced as one (suppressed) exception.

### Added
- `module-mobile-appium` acceptance tests: page source survives a screenshot
  failure, extra-capabilities override mapped ones, positive validation
  (Android via app-package+app-activity, iOS via bundle-id), `@MobileDevice`
  misuse fails clearly, one session reused across Session+Driver of one device
  and closed after the test.
- Container-backed examples wired into CI (Dockerfile warmup, GitLab/GitHub
  jobs); README section on TestForge + Testcontainers + Pact as layers.

### Changed
- Default Spring Boot baseline moved to 3.5.x for a calmer enterprise adoption
  path while keeping Java 21 LTS and Gradle 9.x.
- Default toolchain moved to Java 21 LTS for broader enterprise adoption;
  `ScenarioContext.runScoped(...)` no longer depends on preview JDK APIs.
- Repository hygiene: the personal employer-project analysis note was removed
  from the tree and purged from history; the roadmap hygiene section no longer
  lists concrete previous-employer terms.

## [1.1.0]

### Added
- Optional Testcontainers example (`PostgresSchemaValidationIT`, tag
  `containers`, `containersTest`) — real Postgres without leaving the default
  build offline.
- Optional Allure integration kept at `compileOnly`: `AllureFlowStepDecorator`
  (module-flow), `AllureResourceAttachments` (module-reporting).
- `SchemaValidator` resolves `@Embedded` with `@AttributeOverride`.
- `docs/parallel-tests.md`; scoped-request HTTP example
  (`ScopedRequestTemplateTest`).
- Developed `module-web-playwright` (per-test `BrowserContext`, `Page` fixture
  injection, failure trace/screenshot) and `module-mobile-appium` (device
  matrix, `@MobileDevice` fixtures, failure artifacts, optional local node).
- AI-first docs: `## Agent notes` in every module README, badges.

### Changed
- `ScenarioContext`: `runScoped` carrier for nested context isolation;
  `Waiter` polls in the calling thread (bindings visible to conditions).

## [1.0.0]

### Added
- Thin `core` (typed `ScenarioContext`, polling `Waiter`) plus deletable,
  auto-configured modules: `module-db` (DbWaiter, SQL logging, SchemaValidator,
  repository polling), `module-mock` (scenario-scoped WireMock stubs),
  `module-kafka` (buffer/probe), `module-contract` (field DSL + JSON Schema),
  `module-data` (unique values, generators, `@Prepared` pool), `module-flow`
  (state-machine runner), `module-web` (prewarm), `module-reporting`.
- Offline `example-tests` suite (embedded WireMock + H2), one example per
  module idea.
- P0 production gaps: `ScenarioContextExtension`, scope↔context correlation,
  `application-staging.example.yml`, adaptation checklist, Docker runner image,
  GitLab + GitHub CI.

[Unreleased]: https://keepachangelog.com/
[1.1.0]: https://keepachangelog.com/
[1.0.0]: https://keepachangelog.com/
