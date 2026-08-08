# Changelog

All notable changes to this template are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); the project uses
semantic versioning for its git tags.

## [Unreleased]

### Added
- `module-api-fuzz` v1.2: a valid control request per operation, and
  differential classification against it. Before any mutation, one fully valid
  request built from the same schema and the same configured values proves the
  operation is reachable; only an accepted control lets the run conclude
  anything about validation. Control outcomes are ACCEPTED, REJECTED, BLOCKED,
  FAILED and UNREACHABLE. Verdicts now say only what can be concluded about
  validation — PASSED, OVER_PERMISSIVE, OVER_STRICT, INCONCLUSIVE — while
  crashes, echoes, undocumented shapes and infrastructure answers are recorded
  separately as evidence, so a response with several problems reports all of
  them. Every operation reports which declared constraints the run exercised
  and which it did not, and every finding gets a reproduction manifest carrying
  the case id, seed, control status and a fingerprint of the document it was
  made against.
- `module-api-fuzz` v1.1: schema-aware fuzzing of `application/json` request
  bodies, addressed by JSON path (`createUser/body:$.profile.age/BELOW_MINIMUM`).
  Each case starts from a baseline body built to satisfy every declared
  constraint and changes exactly one field, so a finding points at a field
  rather than an endpoint. Supports nested objects, arrays with
  `minItems`/`maxItems` and item types, `required`, `nullable`, string length,
  `pattern` and `format`, numeric bounds including `exclusiveMinimum`/
  `exclusiveMaximum` and `multipleOf`, `enum`, and `allOf`. A `oneOf`/`anyOf`
  root, a non-JSON media type, or a schema no value can satisfy skips the
  operation with the reason instead of sending a guess.
- `module-api-fuzz`: schema-aware boundary cases on top of `module-api-explorer`.
  Every case is derived from a declared constraint and carries the expectation
  that constraint implies, which is what lets the run report the finding a
  generic fuzzer cannot produce: a service accepting a value its own document
  forbids. Verdicts are SERVER_ERROR, OVER_PERMISSIVE, UNDOCUMENTED_RESPONSE,
  INPUT_REFLECTED, OVER_STRICT, TRANSPORT_FAILURE and PASSED; only the crashes
  fail a build by default. Generation is fully deterministic and every case has
  a stable readable id, so a finding is reproduced with
  `forge.api-fuzz.only-cases` alone; the seed governs which subset runs when
  the matrix exceeds `max-cases-per-operation`. Safety is the explorer's,
  unchanged — off by default, safe methods unless two keys are turned, capped
  and sequential, never a request body. V1 fuzzes parameters only, one at a
  time, with no state between calls.
- `module-api-explorer`: runs an OpenAPI document against a live environment and
  reports what the API actually does. Safe by default — GET/HEAD/OPTIONS need no
  opt-in, anything else needs both the method listed and
  `allow-unsafe-methods=true`. Request values resolve in a fixed order
  (configured override, example, default, enum, deterministic generated value);
  an operation whose required values cannot be resolved is skipped with a reason
  rather than guessed at. Runtime contract verification covers undocumented
  status, unexpected content type, missing required fields, undocumented fields,
  incompatible field types and malformed bodies. Per-operation observations,
  `report.json` and `report.md` are written under `build/api-explorer` with
  deterministic names. Reuses `module-api-discovery` for parsing and the spec
  registry and `module-http` for transport and redaction; adds no second parser
  and no second HTTP client. Stateful sequences, value extraction, replay and
  fuzzing are deliberately out of v1.

### Fixed
- `module-api-fuzz`: an endpoint behind authentication produced a page of green
  validation results. A `401` to valid data and a `401` to invalid data was read
  as "the service rejected bad input"; the same held for 403, 429, redirects and
  5xx. Those responses now make a case INCONCLUSIVE with the reason, and only
  400/422 counts as a validation refusal.
- `module-api-fuzz`: the control request exposed that baseline values for path
  and query parameters ignored their own constraints — the generated
  `"testforge"` is nine characters against a parameter declared `maxLength: 8`.
  Baselines are now constraint-aware and verified before the control is sent.
- `module-api-fuzz`: expectations moved from the case *kind* to the individual
  case, which removed three sources of false findings. A long string was
  reported as `OVER_PERMISSIVE` even when no `maxLength` was declared; a huge
  number likewise with no `maximum`; and the `PATTERN_VIOLATION` case sent one
  fixed string that satisfied common patterns such as `^[a-z0-9-]+$`, accusing
  services of accepting values their documents allowed. A `REJECT` expectation
  is now only ever issued when a declared constraint forbids the value; an
  unknown `format` and an empty string with no `minLength` are probes, while an
  empty string *with* a `minLength` is now correctly proven invalid.
- `module-mobile-appium`: failure-artifact capture no longer aborts the page
  source when the screenshot fails — each artifact is captured independently
  and errors are surfaced as one (suppressed) exception.

### Added
- GitHub Packages distribution for all production modules under
  `io.github.mickeyorlov.testforge`, with manual publishing, authenticated
  Gradle/Maven consumer setup, and remote consumer verification.
- Maven publication for every production module, including POM, Gradle module
  metadata, sources and javadocs; standalone consumer smoke-tests for a real
  `module-http` request and offline `module-api-discovery` report generation.
- `module-api-codegen`: OpenAPI-first Java records and typed `ApiClient`
  skeletons, deterministic generated source directories, JSON/Markdown report,
  stale-output cleanup, offline example, and compile verification of generated
  sources; the standalone Maven consumer verifies its published
  auto-configuration and generation path.
- `module-api-discovery`: OpenAPI endpoint catalog, request/response schema
  shape snapshots, baseline diffs, report artifacts, and offline example test.
- `module-http`: preconfigured REST Assured specification through `ApiClient` —
  base URLs per environment and per service, connect/read timeouts, default
  headers. Filters add the scenario mock scope to outgoing JSON bodies, stamp a
  per-scenario request id, log every call to the `forge.http` logger with
  credentials masked, and optionally retry infrastructure statuses on safe
  methods only (off by default, waits through `Waiter`). `ApiRequestCustomizer`
  and REST Assured `Filter` beans are the extension points for authentication.
- `core`: `ScenarioKeys` holds the correlation ids shared by more than one
  module (`TEST_SCOPE`, `CORRELATION_ID`). `ScopedMockClient.TEST_SCOPE` now
  refers to it, so `module-mock` and `module-http` agree on the scope id
  without depending on each other.
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
