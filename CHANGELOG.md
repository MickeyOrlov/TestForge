# Changelog

All notable changes to this template are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); the project uses
semantic versioning for its git tags.

## [Unreleased]

### Fixed
- `module-api-explorer`: a declared media **range** now covers the response it
  describes. Content types were compared as literal strings, so a document
  declaring `*/*` was treated as incompatible with an `application/json`
  response and the operation was reported as `UNEXPECTED_CONTENT_TYPE`. springdoc
  emits `*/*` for every handler that does not set `produces`, which is the
  default for a stock Spring `@RestController`, so effectively every operation of
  a typical Java service was reported as a contract mismatch — noise that teaches
  a team to stop reading the report. `*/*` now covers any type and `application/*`
  covers any `application` subtype, while exact declarations still match exactly
  and are preferred over a range in the same response, so the schema checked is
  the specific one. Incompatible ranges (`text/*` against `application/json`)
  still fail, and the handling of a missing or blank content type is unchanged.
- `module-api-fuzz`: `--config-file` is now passed to Schemathesis as an
  absolute path. The runner starts the CLI with its working directory set to the
  per-spec output directory, so the previous project-relative argument was
  resolved against that directory and pointed at a doubled path that does not
  exist. Schemathesis aborted before testing anything and no NDJSON report was
  written, so `assertHealthy()` failed with
  `EXECUTION_ERROR: NDJSON report file is missing` — on the documented default
  `output-dir: build/api-fuzz`, which is relative, and therefore for any external
  consumer that did not happen to configure an absolute directory. `--report-dir`
  was already absolute; only this one argument was not. The existing real-CLI
  acceptance test ran from a JUnit `@TempDir`, which is absolute, so the defect
  was invisible to it; a second acceptance test now pins a relative output
  directory.

### Changed
- `module-db-contract`: `assertCompatible()` now fails closed when the check is
  disabled. Previously a disabled check returned a passing report, so a pipeline
  whose `forge.db-contract.enabled` was never set — or was misspelled, which
  nothing else complains about — believed it was gated while no database was ever
  inspected. Since the property defaults to `false`, that quiet pass was the
  un-configured state. It is raised as a configuration failure
  (`IllegalStateException`, naming the property) rather than a schema verdict,
  for the same reason as the missing baseline below: nothing was compared.
  `run()` is unchanged — it still returns a disabled report, still touches no
  database, and remains the entry point for reporting without gating.
- `module-db-contract`: `assertCompatible()` now fails closed when the check is
  enabled and no baseline snapshot exists. Previously it passed, so a pipeline
  whose baseline was never promoted — or was lost by a cache step that did not
  restore it — believed it was gated while every run compared nothing. The
  failure is raised as a configuration failure rather than a schema verdict,
  because nothing was compared; `run()` is unchanged and still captures,
  reports and returns normally without a baseline, which is what bootstrapping a
  new project uses.
- `module-db-contract`: the two places where the check reported "no change" while
  the contract had moved are closed, and the snapshot format is now version 2.
  A partial index's `WHERE` predicate is captured, so turning
  `CREATE UNIQUE INDEX ... WHERE deleted_at IS NULL` into a full unique index —
  a real tightening that rejects writes it used to allow — is reported instead of
  passing silently. Foreign keys carry their `ON DELETE`/`ON UPDATE` actions, so
  switching `CASCADE` to `RESTRICT` is `BREAKING` (a delete that used to succeed
  now fails) and the reverse is `RISKY` (rows now disappear unasked); only the
  action that actually changed is judged. A snapshot written in another format
  version is refused with the fix in the message rather than silently misread,
  which means existing baselines must be re-captured with `writeBaseline()`.

### Added
- `module-db-contract`: the database schema as a versioned contract. Reads one
  schema through SchemaCrawler into a small normalized TestForge model
  (`DbSchemaSnapshot`/`DbTable`/`DbColumn`/`DbPrimaryKey`/`DbForeignKey`/`DbIndex`),
  writes it as deterministic timestamp-free JSON, diffs it against a baseline
  with a bounded comparator, and classifies every change as
  `BREAKING`/`RISKY`/`NON_BREAKING`/`UNKNOWN` with a reason. The contract
  protects readers as well as writers, so tightening nullability is breaking and
  relaxing it is risky. `UNKNOWN` is not a severity: unclassified changes never
  contribute to the report's worst classified verdict and are gated by their own
  switch, independent of the one for risky changes. Snapshot line endings are
  pinned to `\n` so a baseline is byte-identical across the platforms a team
  runs on, and foreign keys leaving the inspected schema record their target as
  `schema.table` so retargeting between same-named tables in two schemas is not
  invisible. PostgreSQL partitioned parents are part of the contract rather than
  invisible to it, and identity columns count as database-supplied so adding one
  is not misreported as breaking. A run that compared nothing — because the check
  is disabled or no baseline exists — says so at WARN instead of passing silently. `report.json` and
  `report.md` are written under `build/db-contract` and registered with
  `ArtifactSink`; `assertCompatible()` is the CI gate, failing on breaking
  changes by default with risky and unknown opt-in. Promoting a baseline is
  always an explicit `writeBaseline()` call — running the check never rewrites
  it. The inspector stays on generic JDBC retrieval so no per-vendor
  SchemaCrawler plugin is needed, and SchemaCrawler is an `implementation`
  dependency whose types never reach the module's API. `module-db` does not
  depend on this module, so a project that only wants `DbWaiter` never gets
  SchemaCrawler. Verified against a real PostgreSQL through Testcontainers.
  Views, triggers, routines, sequences, comments, privileges, default
  expressions, referential actions, environment-vs-environment comparison, ODCS
  export and a CLI are deliberately out of v1.

### Added
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
