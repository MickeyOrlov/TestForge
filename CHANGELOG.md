# Changelog

All notable changes to this template are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); the project uses
semantic versioning for its git tags.

## [Unreleased]

### Added
- Local structural reviews for business-security policies: per-case OpenAPI/schema/
  Link impact, value-free snapshots, input/capture/cleanup preflight, explicit
  review promotion and VALID/REVIEW_REQUIRED/INVALID JSON/Markdown assessments.
  An opt-in gate blocks stale policies before HTTP without earning security
  coverage. Includes local drift/review regressions and published-JAR verification.

### Added
- Offline business scenario drafts from local OpenAPI Links and reviewable path
  hints: bounded dependency graphs, response/request value bindings, explicit
  ambiguity/refusal reports, editable policy scaffolds, review/input requirements
  and published-library wiring. Generation never earns security coverage.

### Fixed
- Run-wide summaries (`http-summary.json`, `db-queries.json`,
  `mock-summary.json`, `flow-coverage.*`, `contract-guard.json`,
  `api-coverage.*`, module-data leftovers) were written under a fixed name
  into the run directory every Spring context of a test JVM shares, so the
  context that closed last overwrote the others: a review on the example
  suite saw 12 HTTP requests of a whole run. `ArtifactSink.newFile` (core)
  hands each context its own file (`http-summary-2.json`, …) under the same
  logical name, and `module-quality` merges them per operation, statement
  shape, flow and documented operation, counting each issue once.
- Business setup controls can create collection resources and feed captured IDs
  into entity probes, while final state checks remain bound to the probe fixture.

### Added
- `module-quality`: run labels (revision, branch, CI job, `run-labels`) in
  `quality.json`, the history and the metrics; `components` with owners
  that group issues by key pattern, each with its own status; accepted
  entries that expire (`# until <date>`) and notes for stale ones;
  `gate.<dimension>.min-score` / `max-new-issues`; `quality-summary.md` for a
  pull request, `quality.prom` in OpenMetrics text and `quality.schema.json`
  for `quality.json`, which now carries `formatVersion` and `measured`.
- `module-quality`: run history (`forge.quality.history.dir`) — one entry per
  run with scores, test outcomes and durations and API operation p95, the
  oldest beyond `keep` dropped. `stability` counts a test that failed and
  passed on the same revision; `efficiency` compares tests with their own
  median + MAD at the run's pace and operation p95 with theirs; every
  dimension lists its score trend, a PASS falling three runs in a row is a
  WARN; the report notes when steady runs leave the baseline behind.
  `qualityGate` takes `-PqualityHistory` and `-PqualityRevision`.
- `module-mock`: `MockSummary` — every closed scope's stubs no request hit
  and scoped requests no stub answered, counted for the run and written as
  `mock-summary.json` to the run index (`forge.mock.summary.enabled`, on by
  default). `module-quality`: `hygiene` scores them, WARN only.
- `module-flow`: `flow-coverage.json` beside `flow-coverage.md`.
  `module-quality`: `flow-coverage` dimension — declared transitions no
  flow took, WARN only.
- `module-db`: `DbQuerySummary` — with `forge.db.queries.enabled`, every
  statement of the run aggregated by shape (count, failures, time, unbounded
  write, slow), written as `db-queries.json` to the run index.
  `module-quality`: `db-queries` dimension from it — FAIL on a failed or
  unbounded statement, WARN on a slow one.
- `module-http`: `HttpTrafficSummary` — per operation (method and path
  shape), requests, status classes, transport failures and p50 / p95 / max
  latency, written as `http-summary.json` to the run index.
  `module-quality`: `reliability` dimension from it — operations that
  answered 5xx or failed in transport, even where the test expected it.
- Business security policy suites over OpenAPI: protected fields, owner/foreign
  users, explicitly declared tenants, state transitions and replay with persisted
  state verification. No-traffic candidate sequences and complete operation ×
  role × kind coverage, explicit exclusions, bounded isolated HTTP transport,
  shared findings/gates and JSON/Markdown matrices. Includes declarative JSON
  policies and secure/intentionally vulnerable offline examples.

### Fixed
- `module-quality`: each dimension reads its producer's report by file name.
  The format-version check read every JSON of a category, so db-contract's
  schema snapshot (a layout of its own, version 2) made the data dimension
  NOT MEASURED next to a report with a breaking change — found by
  `QualityEndToEndTest`.
- Incomplete security evidence cannot produce a clean quality score or pass a
  selected security gate through baseline acceptance. Observed high findings
  remain FAIL. Artifact secret leaks cannot be accepted or baseline-promoted.

### Added
- `module-quality`: `api-coverage` dimension — the share of documented
  operations the suite called, from a new `api-coverage.json` the contract
  guard writes beside `api-coverage.md`; uncalled operations are its issues,
  `forge.quality.min-api-coverage` makes a low share fail.
- `example-tests`: `org.gradle.test-retry` 1.6.6, off by default —
  `-PtestRetries=N` reruns failed tests; one that passes on a rerun keeps the
  build green and reaches the quality gate's stability dimension as flaky.
- `example-tests`: `QualityEndToEndTest` — contract guard, contract monitor,
  db-contract, access matrix, api-fuzz and passive security run with a
  planted defect into one run index; module-quality must find each in its
  dimension, so a producer that changes what it writes breaks a test.
- `module-quality`: how much was measured — `measured: N of 9 dimensions`
  in the report and `forge.quality.required` dimensions that fail the gate
  while NOT MEASURED; `forge.quality.results-not-before` drops stale JUnit
  suites (the `qualityGate` task sets it when the test tasks run in the same
  build); a module report with an unknown `formatVersion` is NOT MEASURED
  instead of read as zero. `module-api-fuzz` `findings.json` and
  `module-api-explorer` `contract-guard.json` now carry `formatVersion: 1`.
- `module-security` B3: digest-pinned Docker ZAP delegation through an
  allowlisted, read-only, rate/request/time-limited loopback gateway. Sanitized
  OpenAPI input, explicit-page baseline mode, value-free normalized findings,
  private scanner evidence cleanup and separate real-container acceptance.
- Security reports distinguish measured/complete and account for enabled but
  unexecuted blocks; ZAP findings resolve only under the same completed policy.


### Added
- `module-security` B4/B5: allowlisted, bounded JDK TLS/redirect assessment and
  explicit pre-upload artifact secret scanning. Local TLS algorithm limits
  remain unmeasured; symlinks/read/size limits are visible. Known credentials
  stay in process memory only; artifact leaks cannot be accepted past the gate.


### Added
- `module-security` B2: bounded, allowlisted authentication/access probes using
  existing roles, JWT signer, request planner and access matrix. Explicit
  fixture ownership, positive controls and two-key write permission; session
  rotation/logout and malformed-request fixtures share the value-free report.


### Added
- `module-security`: opt-in passive API checks, value-free findings and coverage,
  explicit gates/baselines and Markdown/JSON/SARIF reports in the run index.


### Changed
- `module-web`: prewarm visits each page once per JVM instead of running once
  per JVM: a second Spring context with other pages (another profile or
  environment) now warms those instead of skipping silently.
- `module-api-codegen`: generated names are built whole — the client for
  untagged operations is `DefaultApiClient` (was `DefaultTypeApiClient`), a
  `class` tag gives `ClassApiClient`, and inline response models read
  `ListTasks200Response` / `ListTasksDefaultResponse` (were
  `ListTasksN200Response` / `ListTasksDefaultTypeResponse`). The failure
  message of `assertGenerated()` lists the first breaking changes.
- `module-contract-monitor`: each case validates up to
  `forge.contract-monitor.sample-size` (default 20) newest matching messages
  instead of only the newest; violations carry their share of the sample and
  the shape snapshot is the union of the sample's shapes, so an optional field
  one message omits no longer reads as removed.
- `module-flow`: a successful run no longer writes `flow-path-<name>.txt`
  unless `forge.flow.publish-successful-paths=true` — a suite runs flows
  hundreds of times and the run index flooded; failed runs still always
  write theirs, and `flow-coverage.md` covers what succeeded.
- `module-kafka`: the collector joins a consumer group of its own per run
  (`forge.kafka.unique-group-id`, default `true`): two runs sharing one group
  split the partitions and each missed part of the messages.

### Fixed
- `module-quality`: the newest-run default skips a run index that holds only
  `module-quality`'s own report, which the gate's JVM leaves behind — a second
  gate run judged that instead of the tests.
- `module-rabbitmq`, found by reviewing it against a real broker: a
  stopped collector kept its consumer alive in Spring's channel cache, so its
  queue stayed and messages kept arriving — it now cancels the consumer and
  closes the channel physically, as the publisher does with its confirm-mode
  channels; and a String payload that is JSON is sent as `application/json`,
  which Spring AMQP's Jackson converter requires.
- `module-db`, found by `PostgresDbChecksIT` (tag `containers`), the table
  diffs, data rules and query checks run against PostgreSQL 17:
  - table and column names a test gives are quoted for the database, folded
    the way it folds unquoted names, so `db.table("order")`, invariants and
    `into(...)` work on reserved words (`order`, `user`) instead of failing
    with a syntax error;
  - an array column (`text[]`) compares by its elements: an untouched one no
    longer reads as changed on every diff;
  - `watchSchema()` escapes `_` in metadata lookups, so a table without a
    primary key is keyed by its own columns, not also by those of a
    neighbour whose name the pattern matches (`audit_log` / `auditxlog`);
  - a `SELECT` over a writing CTE (`WITH moved AS (UPDATE … RETURNING …)
    SELECT …`) is that write: its kind is `UPDATE`/`DELETE`/`INSERT`, and an
    `UPDATE` or `DELETE` body without a `WHERE` is an unbounded write.
- `module-db`: query checks read a statement's kind past leading comments
  and parentheses (Hibernate's `use_sql_comments` made every statement
  `OTHER`), treat `WITH … UPDATE` as an update, and judge a write by its own
  top-level `WHERE` — a `WHERE` inside a CTE or subquery no longer makes an
  `UPDATE` of every row look bounded. Captured statements hold a thread id,
  not the `Thread`. `DbQueries.open()` keeps the statements of an action that
  threw.
- `module-db`: `watchSchema()` failed with a SQL syntax error on a table
  named after a reserved word (`order`, `user`); discovered tables are now
  quoted exactly as the metadata names them. A table over the row limit names
  the exclusion to use. `assertHoldsWithin` failed only at the deadline when
  a rule could not run at all (a typo in a condition); it now fails at once.
- `core`: the `Waiter` timeout messages above first stopped retrying a
  condition that threw an `AssertionError` (Awaitility's `ignoreExceptions()`
  had retried it), so an `assertThat` inside a wait failed on its first poll.
  Assertion errors are "not ready yet" again, and the last one is reported.
- `module-mobile-appium`: a session that could not start surfaced Selenium's
  message, which quotes every capability — a device cloud's `accessKey`
  included — and the hub URL with its user info; it is now reported with the
  device, the redacted hub and a scrubbed first line, without the original
  exception attached. An invalid hub URL is no longer echoed. A failure in
  `@BeforeEach`/`@AfterEach` leaves the same artifacts as a failing test,
  captured once.
- `module-mobile-appium`: the local Appium node's output went to a pipe
  nobody read; once its buffer filled the node blocked on its next log line,
  mid-suite. It now goes to `<artifacts-dir>/appium-node.log`. A node that
  exits before answering fails the start at once with its exit code and the
  log's last lines, instead of waiting out `startup-timeout` without a reason.
- `module-web-playwright`: a failure in `@BeforeEach`/`@AfterEach` — a
  sign-in through the injected `Page` — left no screenshot, trace or
  `browser.md`; lifecycle failures are captured too, once per test. The
  browser type is lower-cased with `Locale.ROOT` (under a Turkish locale
  `CHROMIUM` became `chromıum`), and a launch failure names the install
  command.
- `module-web`: a failed visit's error quoted the URL as configured, so
  basic-auth credentials in a staging URL reached the log, `prewarm.md` and the
  failure message; errors are now scrubbed to the redacted URL. A page a
  browser echoed back normalized (`https://s.test` → `https://s.test/`) was
  reported as redirected; URLs are compared normalized.
- `module-db-contract`: `writeBaseline()` recorded an empty schema (a wrong
  schema name or an `include-tables` pattern matching nothing) — a baseline
  under which every table reads as added and the gate can never fail. It now
  refuses, and `assertCompatible()` fails closed on an existing empty
  baseline. A baseline captured for another schema is refused instead of
  diffed into every table removed and added.
- `module-api-fuzz`: a run whose Schemathesis process died before writing a
  report parsed the previous run's report from the same directory and
  presented its findings as this run's. Only a report the run itself wrote
  (new or rewritten since it started) is parsed; otherwise the spec is an
  execution error that says older reports were ignored.
- `module-api-fuzz`: `UrlRedactor` removed only user info, so an `api_key` or
  signature in the base URL's query string reached `run.json` and
  `report.md`; every query value is now masked.
- `module-api-fuzz`: errors name their spec, and a process's stderr is cut to
  its last 20 lines (2000 characters) instead of a whole traceback.
- `module-db`: `DbWaiter`'s Javadoc still linked the old
  `awaitRowCount(String, String, int)` and broke the Javadoc task that
  `publishTestForgeLibraries` runs (the default build does not); its example
  concatenated a value into SQL and now binds it.
- `module-api-codegen`: a `$ref` to a schema the document does not define
  crashed generation with a `ConcurrentModificationException` (reported as
  the error `null`); it now maps to `Object` with a warning. A failure
  without a message is reported by its exception type.
- `module-api-codegen`: the compile check no longer trusts `java.class.path`
  alone — it adds the locations ApiClient, REST Assured and Jackson were
  loaded from, and skips with the reason (instead of failing every spec)
  when they sit in a Spring Boot fat jar.
- `module-api-codegen`: documents Java could not take as they were now
  generate compiling code — a schema named `Error` no longer makes the client
  ambiguous (clients import models by name), models named like a type the
  generated code uses (`String`, `Response`, `ApiClient` …) get a `Model`
  suffix, properties named like an `Object` method (`hashCode`, `toString`,
  `getClass`, `wait` …) get a `Value` suffix with their wire name kept, and
  parameters named `api` or `requestSpec` no longer collide with the
  generated method's locals.
- `module-contract-monitor`: `<case>.message.json` wrote Kafka header values
  as they were — tokens and personal data included — despite the payload
  being redacted; it now lists header names only.
- `module-reporting`: a suite with several Spring contexts produced one run
  directory per context, each with a partial index — or, with a fixed
  `run-id`, an index holding only the last context's artifacts. The run id is
  now generated once per JVM and every context writes the union of the
  run's artifacts.
- `module-contract`: an invalid JSON Schema failed with "Schema contract is
  not a valid JSON Schema" and no hint which one; the message now names the
  contract and the parser's reason.
- `module-state`: a tag a recipe could not map surfaced as a bare
  exception, and a recipe that materialized nothing as a
  `NullPointerException`; both are now `StateRecipeException`s naming the
  recipe and the tags.
- `module-state`: every recipe's flow ran under the one name `flow`, so
  flow coverage and path artifacts mixed all recipes together; it now runs
  under `StateRecipe.name()` (`state:<Type>` by default).
- `module-flow`: `FlowContext.put(key, null)` made a flow that reached its
  target fail with a `NullPointerException` while building the result; a
  null now removes the key, matching how `get` already treated it.
- `module-kafka`: the collector's startup waits until partitions are
  assigned and positioned, so an event sent right after the context starts is
  no longer lost with `auto-offset-reset: latest`; a collector that stopped on
  a Kafka error now says so in every later `awaitMessage` timeout.
- `module-mock`: a blank scope id is rejected (it would have matched requests
  that carry no scope), and a second `close()` of a scope does nothing
  instead of publishing an empty artifact over the real one.

### Added
- `example-tests`: a `qualityGate` task — the quality gate as a CI step,
  after `test` / `containersTest`, pointed at their JUnit results, with
  `-PqualityRunIds` and `-PqualityBaseline`; `QualityGateTest` (tag
  `quality-gate`) is excluded from `test`.
- `module-gherkin`: `forge.gherkin.scope-messages` limits message steps to
  messages carrying the scenario's scope header — without it a step could be
  satisfied by another scenario's event; `KafkaScenarioDiagnostics` /
  `RabbitScenarioDiagnostics` attach a failed scenario's message traffic to
  it and to the run index. The example suite runs scenarios in parallel.
- `module-gherkin`: database and mock steps — `io.testforge.gherkin.db`
  waits for a row, proves one absent within `forge.gherkin.settle`, and checks
  what changed in a watched table; `io.testforge.gherkin.mock` stubs and
  verifies a downstream in the scenario's own scope, closed after it.
- `module-gherkin`: message steps — `io.testforge.gherkin.kafka` and
  `io.testforge.gherkin.rabbitmq` publish, await by key / routing key / JSON
  path, check exactly one or none within `forge.gherkin.settle`, and remember
  a field; `VariableSteps` gives run-unique values and checks remembered ones.
  Examples run on an embedded Kafka broker and, tagged `@containers`, on
  RabbitMQ 4.1.
- `module-quality`: `security` dimension (step A4) from `module-security`'s
  `security-findings.json` — new findings weighted 40 / 20 / 5 / 1 by
  severity, FAIL on a new critical or high; NOT MEASURED when the security
  run measured nothing. `forge.quality.run-id` takes a comma-separated list
  to judge the runs of several JVMs as one.
- `module-quality`: dimensions from the other modules' reports (step A3) —
  `contracts` (contract guard, contract monitor), `data` (db-contract
  BREAKING / RISKY / UNKNOWN), `robustness` (unaccepted fuzz finding groups),
  `authorization` (access-matrix violations) and `efficiency` (tests at least
  2× and 1 s slower than in the baseline). A module that is not used leaves
  its dimension NOT MEASURED, naming the module.
- `module-api-fuzz`: `findings.json` next to `report.md` — the finding groups
  with spec, key, check, operation, occurrences and accepted flag.
- `module-quality`: baseline comparison (step A2). Each dimension names its
  issues by stable keys; against `quality-baseline.json` they are new,
  accepted (`forge.quality.accepted`), known or resolved, and
  `assertAcceptable()` fails only on new ones. `writeBaseline()` promotes a
  run explicitly; the check never writes the baseline itself.
- `module-quality`: judges a finished run from its JUnit XML (Gradle,
  Surefire) and run index — `functional`, `stability` (flaky = failed and
  passed in one run) and `hygiene` dimensions, each with a status, a score
  and the printed formula behind it; `quality.md` / `quality.json` in the run
  index; `assertAcceptable()` fails on `fail-on` dimensions and closed when
  disabled or when nothing was measured
  ([spec](docs/quality-security-spec.md), step A1).
- `module-gherkin` (optional): Cucumber steps over the TestForge modules for
  teams that write feature files. Every scenario opens with a cleared
  `ScenarioContext`, its own scope and correlation id; `ScenarioVariables`
  carries `${name}` values between steps; `io.testforge.gherkin.http` sends
  requests through `ApiClient` and checks status and JSON fields. Each
  module's steps are a glue package of their own, the module `compileOnly`
  ([plan](docs/gherkin-layer-roadmap.md)).
- `module-rabbitmq`: `RabbitDiagnosticsExtension` — a failed test writes
  `module-rabbitmq/<test>.md` to the run index: what it published and what
  arrived while it ran, scoped to the scenario, bodies only with
  `forge.rabbitmq.diagnostics.payloads=true`. `core`'s
  `MessageTrafficReport` renders it, and `KafkaDiagnosticsExtension` now
  uses the same renderer (its report is unchanged).
- `module-rabbitmq`: the second broker adapter. `RabbitCollector` reads a
  server-named, exclusive, auto-deleted queue of its own bound to
  `forge.rabbitmq.bindings`, so it never takes a service's messages;
  `RabbitProbe` / `RabbitMessageFilter` wait by exchange, routing key,
  header, body fragment or JSON path (exactly one, none within a window);
  `RabbitPublisher` publishes with the scenario's scope and correlation
  headers, waits for the confirm and fails on an unroutable message.
  `RabbitMqProbeIT` runs it against RabbitMQ 4.1 (`containersTest`).
- `core`: `io.testforge.core.messaging` — `BrokerMessage`, `MessageBuffer`,
  `MessageCriteria`, `MessageQuery` and `MessageProbe`, the broker-neutral
  part of `module-kafka` (buffer, criteria, await / exactly one / none within
  a window, closest messages on a timeout), for a second broker adapter.
  `module-kafka` is rebuilt on it; its public API and messages are unchanged
  ([plan](docs/messaging-layer-roadmap.md)).
- `module-db`: `CapturedQueries` measures time and failures —
  `slowest(n)`, `totalMillis()`, `failed()`, and `expect().noneSlowerThan(limit)`,
  `totalUnder(budget)`, `noErrors()` (a statement that failed inside an action
  that succeeded, such as a duplicate key the code swallowed).
- `module-db`: atomicity checks on `DbSnapshot` and `TableSnapshot` —
  `expectRolledBack(action)` (the action must fail and leave every watched row
  as it was, or the failure lists what it left), `assertUnchanged()` for a
  rejection without an exception, `assertUnchangedWithin(timeout)` for an
  asynchronous compensation.
- `module-db`: `CapturedQueries.expect().noUnboundedWrites()` (no `UPDATE`/
  `DELETE` without `WHERE`, no `TRUNCATE`) and `noUnfilteredReads(allowed...)`
  (no `SELECT` of a whole table outside an allow-list of reference tables).
- `module-db`: query budgets — with `forge.db.queries.enabled=true`,
  `DbQueries.during(action)` captures every statement any thread ran during
  an action (`duringThisThread` for the caller only), and
  `expect().atMost(n)`, `atMost(n, kind)`, `none(kind)`, `noRepeated(times)`
  catch an action that runs too much SQL, writes from a read, or runs one
  statement once per row (N+1).
- `module-db`: `TableDiff.expect().onlyOwnData(tagColumn)` — every row an
  action added, changed or removed carries a test tag from `module-data`'s
  `DataIsolation`; data no test owns fails, other tests' rows are tolerated
  (`onlyThisTestsData` is the strict form). `RowChange.values()` holds the
  changed row's current values.
- `module-db`: cross-table invariants — `everyRowHas("orders.id",
  "outbox.aggregate_id")`, `totalMatches(...)` (a stored total equals the sum
  of its parts) and `countMatches(...)` — and `assertHoldsWithin(timeout)`,
  which polls until every rule holds and fails with those still broken.
- `module-db`: data invariants — `DbState.invariants()` with `noOrphans`,
  `unique`, `allowedValues`, `notNull` and `holds(table, condition)`, scoped
  per table with `within(...)`; `assertHolds()` lists every broken rule with
  its row count and up to five offending rows.
- `module-db`: side-effect containment — `DbState.watch(queries...)` snapshots
  several tables and `diff().expect().table(name, t -> ...).nothingElse()` /
  `onlyChanged(...)` fails, with the diffs, when a table moved that the test
  did not name. `watchSchema(excluded...)` watches every table of the schema
  (primary key as row key) for databases a test owns.
- `module-db`: row expectations on a table diff —
  `expect().row(42).changed("status", "NEW", "PAID").increasedBy("version", 1)
  .movedForward("updated_at").unchanged("created_at").onlyThese()`, plus
  `addedRow(...).has(...)` and `removedRow(...)`. Values compare by meaning
  across JDBC types; moves in `ignoring(...)` columns are kept for these
  checks without making a row count as changed.
- `core`: `ScenarioContext.remove(key)` drops one value; `put(key, null)`
  pointed at "remove semantics" that did not exist. `JsonPath.read` refuses a
  null path with a hint instead of a bare `NullPointerException`.
- `core`: `Waiter.await(description, supplier, until, failWhen)` stops at
  once with `WaitAbortedException` (an `AssertionError`) when the value
  reaches a state it will not leave — a `Failed` job is not waited on for the
  full timeout. `withTimeout(...)` / `withPollInterval(...)` give one wait its
  own deadline or cadence; polls are never spaced wider than half the deadline
  (Awaitility refused an interval as long as the timeout).
- `core`: a `Waiter` timeout throws `WaitTimeoutException` (still a
  `ConditionTimeoutException`) naming the last value the condition saw, the
  last error a poll threw and the number of polls — instead of only "did not
  complete within 30 seconds" while every poll's exception was ignored.
- `module-mobile-appium`: a failing mobile test also leaves `session.md`
  (device, platform, hub, session id, the capabilities sent with secret-looking
  values masked) and `device.log` (the last 200 lines of `logcat`/`syslog`);
  every artifact carries the test's `testKey` so the run summary groups it
  with the test's other reports.
- `module-web-playwright`: `forge.playwright.download-browsers=false` stops
  Playwright from downloading every bundled browser when one is missing —
  for CI images that bake browsers in.
- `module-web-playwright`: `forge.playwright.base-url` for relative
  navigation, and the scenario's correlation id (`X-Request-Id` by default,
  as in `module-http`) on every browser request to that origin — never to
  third-party origins, where a custom header forces a CORS preflight.
  `browser.md` names the id.
- `module-web-playwright`: a failing UI test also leaves `browser.md` —
  requests that failed or answered 4xx/5xx, uncaught page errors, console
  errors and warnings, from every page of the test's context — written before
  the screenshot and registered under the test's `testKey`. URLs are
  redacted; headers and bodies are never recorded.
- `module-web`: `forge.prewarm.mode=http` warms with plain GET requests that
  follow redirects — no browser download; client bundles stay cold, so
  `browser` remains the default. `PrewarmExampleTest` exercises prewarm end
  to end offline against WireMock.
- `module-web`: prewarm reports what each page answered — status, where it
  ended after redirects, time, error — one log line per page and `prewarm.md`
  in the run index (`PrewarmRunner.lastReport()`), URLs redacted.
  `forge.prewarm.fail-when-unreachable` (default `false`) stops the suite at
  startup when not one page answers. Visiting sits behind a `PrewarmEngine`
  bean (default: the browser).
- `module-db-contract`: every change says which of the project's JPA entities
  it reaches (`DbChangeAssessment.usedBy()`), shown as a "Used by" column in
  `report.md` and in the gate's failure message. Entities are read lazily from
  the persistence units' metamodel; replace the `DbSchemaUsage` bean to
  describe usage another way. Verdicts are unchanged.
- `module-db`: `SchemaValidator.mapping(entity)` returns an `EntityMapping`
  (table and mapped columns) resolved without touching a database.
- `module-db-contract`: likely renames are pointed out — a removed and an
  added table, column, index or foreign key with an identical shape are
  paired under "Possibly renamed" in `report.md` and the gate's failure
  message (`DbContractReport.renameCandidates()`). Verdicts are unchanged.
- `module-api-fuzz`: `forge.api-fuzz.accepted-findings` lists known finding
  groups (`<check> <operation>`, optionally `<spec>:`-scoped, ` # comment`
  allowed); `fail-on-findings` then fails only on new groups
  (`ApiFuzzReport.newFindingGroups()`), `report.md` marks accepted ones and
  lists accepted entries no run matched any more (`staleAcceptedFindings()`).
- `module-api-fuzz`: findings carry their spec and group by check and
  operation (`ApiFuzzReport.findingGroups()`); every run writes
  `<output-dir>/report.md` with one entry per group — cases, phases, methods,
  first message line — and the command that reproduces it (same seed and
  config, URL redacted, `--include-name` for the operation). The
  `fail-on-findings` message names checks, not every case.
- `module-api-codegen`: each generation writes `<spec>/surface.json` (client
  methods and record components, types only) and reports what changed for the
  tests that use the sources — a removed method or changed signature as
  `BREAKING`, renamed or reordered parameters of the same types as `RISKY`, a
  new component of a record tests build as `BREAKING`. The baseline is the
  previous run or `forge.api-codegen.surface-baseline-dir`;
  `forge.api-codegen.fail-on-breaking-changes` turns it into a gate.
- `module-api-codegen`: generated clients and records carry the document's
  words as Javadoc — tag, summary and description, the call with the statuses
  it responds with (model named where one exists), parameter and property
  descriptions. Document text is escaped so it cannot end the comment and add
  code to a generated class.
- `module-api-codegen`: every generated spec is compiled right after it is
  written (`forge.api-codegen.compile`, default `true`) into
  `<output-dir>/<spec>/classes`; a spec that does not compile fails and the
  report lists the errors by file and line. Without a JDK compiler the check
  is skipped with a warning.
- `module-contract-monitor`: `ShapeDiff.impact()` judges each drift for
  consumers (`BREAKING` for a vanished path or a newly possible type, `SAFE`
  for new paths and narrowed types), listed in `report.md`;
  `forge.contract-monitor.fail-on-breaking-drift-only=true` fails only on
  breaking drift.
- `module-api-explorer`: operation coverage. The contract guard counts the
  documented operations the suite's `ApiClient` calls reached;
  `api-coverage.md` in the run index lists those no test called
  (`ContractGuard.coverage()`), report-only.
- `module-api-discovery`: every catalog and shape difference is classified
  for clients (`BREAKING`, `RISKY`, `UNKNOWN`, `SAFE`; requests and responses
  judged in opposite directions) in `ApiSpecReport.changes()` and
  `report.md`. `forge.api-discovery.fail-on-breaking-only=true` fails only on
  breaking changes.
- `module-reporting`: `summary.md` gains *Failed tests* — each failed
  test's HTTP, SQL, Kafka and UI diagnostics listed under `<Class>-<method>`
  (a `failed-test-*` report's name or a producer's `testKey` metadata, which
  Playwright failure artifacts now carry).
- `module-contract`: `ContractChanges.compare(before, after)` classifies
  every difference between two schema versions for consumers of the old one
  (`BREAKING`, `RISKY`, `UNKNOWN`, `SAFE`); `assertNoBreaking` fails a review
  job on breaking changes.
- `module-contract`: `ContractDrafts.fromSamples(name, samples)` drafts a
  JSON Schema from real payloads — required fields, union of types, merged
  array items — with no sample values copied, as a starting point to review
  and commit.
- `module-state`: `StateRecipe.targets()` declares the tags a recipe can
  prepare; an unknown tag fails with the supported ones.
  `StateRecipeExecutor.verify(recipe)` and the `StateRecipes` bean
  (`assertAllValid()`) check every recipe's flow for every target without
  running a step.
- `module-state`: `StatePreparation.duration()` — what a prepared state cost;
  the steps are in `flowResult().transitions()`.
- `module-flow`: transition coverage. Runners record declared transitions
  taken into the shared `FlowCoverage` per flow name; `flow-coverage.md` in
  the run index lists those no test took (report-only, `uncovered` count in
  metadata).
- `module-flow`: declared transitions. `FlowStep.of(state, action, next...)`
  states where a step may go; returning anything else fails at once.
  `FlowRunner.verify(start, target)` / `assertValid` find dead ends and an
  unreachable target before running, `describe()` renders a Mermaid state
  diagram. Optional: `FlowStep.declaredNext()` defaults to empty.
- `module-flow`: timed transitions. `FlowResult.transitions()` lists every
  step taken with its duration (`FlowTransition`), `duration()` the total; a
  timed-out flow names its slowest step, a failed step says how long it ran,
  and the flow-path artifact lists the timed steps.
- `module-kafka`: `KafkaDiagnosticsExtension` — a failed test publishes
  `module-kafka/<Class>-<method>.md` with the events it published and the
  messages buffered while it ran (only its own scope's when
  `publish.scope-header` is set). Positions, keys and header names;
  payloads only with `forge.kafka.diagnostics.payloads=true`.
- `module-kafka`: `KafkaPublisher.publish(topic, key, payload[, headers])`
  sends a test's own event (String as is, objects as JSON) and waits for the
  acknowledgement; `forge.kafka.publish.scope-header` / `correlation-header`
  add the scenario's ids from `ScenarioContext`, `publish.headers` static ones.
- `module-kafka`: `awaitExactlyOne(filter, settle)` catches a duplicate,
  `assertNoMessage(filter, window)` the event that must not be sent, and
  `findMessages(filter)` returns every match. An `awaitMessage` timeout now
  lists the closest buffered messages with the criteria each failed (by name,
  never the payload), or says the topic had nothing buffered.
- `module-api-explorer`: stub contract (`forge.stub-contract`).
  `StubContractChecker.assertHonoured(wireMock)` or `assertHonoured(stub)`
  checks what WireMock stubs answer against the downstream's OpenAPI document
  with the contract guard's checks; URL patterns, faults, body files and
  templated bodies are reported as not checked. `strip-prefix` for mock URL
  prefixes. WireMock is optional (`compileOnly`).
- `module-mock`: unused stubs. `MockScope.unusedStubs()` names scoped stubs
  no request hit (`POST /refunds → 409`, or a sequence step never reached);
  the scope artifact lists them under `unusedStubs`. With
  `forge.mock.fail-on-unused-stubs=true` closing such a scope fails after its
  stubs are removed — suppressed under the test's own failure if it had one.
- `module-mock`: `MockScope.sequence(request, responses...)` answers the same
  request differently each time, then repeats the last response — for retry
  tests (`503, 503, 200`). A WireMock scenario per scope keeps parallel
  scenarios' sequences apart; every step is a scoped stub removed on close.
- `module-db`: `DbWaiter.awaitValue(description, sql, expected, params...)`
  waits until the first column equals a value (numbers by value);
  `awaitRowCount` takes `?` parameters too. Both time out with what the
  database last answered — the value, "no row", or why the query failed —
  still as a `ConditionTimeoutException`.
- `module-db`: setup rows. `db.into("orders").keyedBy("id").insert(row)`
  inserts one row, returns its key (given or generated) and registers its
  `DELETE` with `module-data`'s `Cleanup`, so it is removed after the test
  even when it fails, children before parents. New module edge
  `module-db → module-data`.
- `module-db`: SQL in failure diagnostics. With
  `forge.db.diagnostics.enabled=true` and `@ExtendWith(DbDiagnosticsExtension.class)`,
  a failed test publishes `module-db/<Class>-<method>.md` — its last
  statements with datasource, duration, bound parameters (per batch entry)
  and errors. `SqlJournal` keeps them per thread (`last`, default 20).
- `module-db`: `DbState` table snapshots. `db.table("orders").keyedBy("id")
  .where("customer_id = ?", id).take()` before an action, `.diff()` after:
  added, removed and changed rows per column, read with plain JDBC.
  `expect().added(0).removed(0).changed(1, "status")` fails with the whole
  diff, including changes in columns that should not have moved.

### Changed
- `module-auth` is Beta (was Experimental).

### Added
- `module-auth`: a static token that is a JWT carries its `exp`; once it has
  passed, the role fails with "Static token for role 'x' expired at …" instead
  of every request coming back 401.
- Failure diagnostics say which role each HTTP exchange ran as:
  `module-auth`'s filter leaves a note (`admin (header Authorization)`,
  `anonymous (no credential)`, `… set by the test`) under
  `RequestAttributes.AUTH` in `core`, and `HttpDiagnosticsExtension` prints
  it as `As:` above each exchange. `HttpExchangeJournal.Exchange` gains `auth`.
- `module-auth`: two more ways in. `type: api-key` sends a fixed key in a
  header (`X-API-Key` by default) or a query parameter; `type: session` logs
  in once (JSON or form) and sends the session cookie the login sets, cached
  until its `Max-Age`. A parameter or cookie the test set itself is never
  replaced. `TokenProvider.placement(role)` says where each role's
  credential goes.
- `module-http`: `Redactor.uri` masks secret query values in HTTP logs and
  failure diagnostics (`?api_key=***`).
- `module-api-explorer`: access matrix (`forge.access-matrix`). Every
  operation of the document is called once per role — including the reserved
  `anonymous`, which sends no token — and each status is held to a declared
  rule (`allow`, `deny`, `404`, `2xx`, …) per role or per operation.
  `AccessMatrixRunner.assertHealthy()` fails on violations and errors;
  `access-matrix.md` shows violations, the matrix and the expectations the
  run would propose for undeclared cells. Same parameter values and method
  safety policy as exploration; statuses only, never bodies.
- `module-auth`: reserved role `AuthContext.ANONYMOUS` — no token even with
  `default-role`.
- `module-data`: leftovers in the run index. Every cleanup action that could
  not be undone is recorded (test, description, first line of the error, the
  isolation tag the data carries, whether the test failed too) and published
  when the context stops as `module-data/leftovers.json` with
  `healthy: false`, so `summary.md` lists it under *Needs attention*. A clean
  run writes nothing.
- `module-data`: data isolation for shared environments. `DataIsolation`
  carries a run tag (`tf-k3x9q`, or `forge.data.isolation.run-tag` such as a
  CI run id) and, with `DataIsolationExtension`, a per-test part
  (`tf-k3x9q-t4`); `tag(value)` and a factory's `f.tagged()` put it into
  values, `owns`, `ownedByRun` and `isTestData` filter what the environment
  returns or what a sweeper may remove. `Factory.create(persist, undo)`
  persists a built object and registers its undo with `Cleanup`.
- `module-data`: `Factory` builds test objects and payloads with readable,
  unique defaults — `Factory.forRecord(Customer.class)`, `Factory.map("order")`
  or `Factory.of(name, assembler)` — overridden per field (`with`, `without`),
  with named traits (`as("vip")`) and derived fields that follow overrides.
  Immutable, so a `static final` declaration is safe in parallel tests;
  `f.label()` and `f.unique(type, generator)` keep values distinct. Unknown
  fields or traits, missing record components, wrong component types and
  cycles fail with a readable message. Plan: `docs/data-layer-roadmap.md`.
- `module-data`: test-data cleanup. `Cleanup.register("order " + id, () -> ...)`
  records how to undo what a test created; `CleanupExtension` runs it after
  the test — newest first, every action even if one fails, also when the test
  failed — and `@BeforeAll` registrations after the class. Per thread. A
  cleanup problem is attached to the test's failure as suppressed; a passing
  test that leaves data behind fails with every action that could not be undone.
- `module-http`: `Pagination` walks every page of a list endpoint with one
  call — `byPageNumber`, `byOffset`, `byCursor`, `byLinkHeader` (RFC 8288
  `rel="next"`) — mapping items with REST Assured's JSON path. It fails
  instead of misbehaving on a non-2xx page, on the same page served twice
  (a server ignoring the paging parameter) and after `maxPages` (default 100).
- `module-http`: `ResponsePoller` repeats a call until its response matches
  (`poll.until(() -> api.request().get(...), jsonPath("status", "DONE"))`)
  through `Waiter` and `forge.wait`, overridable per call. Named
  `ResponseConditions` (`status`, `jsonPath`, `allOf`, `matching`) make the
  timeout message say what was awaited, with the attempt count and the last
  response's status and redacted body.
- `module-http`: failed-test diagnostics. The scenario's last exchanges
  (`forge.http.diagnostics.last`, default 10) are kept in memory per thread,
  redacted like the log; with `@ExtendWith(HttpDiagnosticsExtension.class)` a
  failed test publishes them to the run index as `module-http/<Class>-<method>.md`.
  The original failure is always rethrown; passing tests write nothing.
- Contract guard (`module-api-explorer`, `forge.contract-guard.enabled`):
  every response `ApiClient` receives in ordinary functional tests is checked
  against the OpenAPI document with the explorer's checks. `record` mode
  (default) logs each new finding once and publishes `contract-guard.json`
  with a `healthy` verdict to the run index; `fail` mode fails the call that
  received a drifted response. Calls the document does not describe are
  reported as `UNDOCUMENTED_OPERATION`. Sends no extra requests.
- `module-reporting`: `summary.md` is actionable. A *Needs attention* section
  (only when something failed) lists reports whose own verdict is negative
  (`healthy: false`, `compatible: false`, `outcome` `FINDINGS` / `*_ERROR`);
  every entry shows the producer's verdict, and reports kept outside the run
  directory show their configured `location` instead of a bare file name.
  Absolute, drive-qualified and `..` locations are never printed.

## [1.2.0] - 2026-10-31

### Added
- `module-api-explorer`: stateful exploration, step 4 — replay.
  `forge.api-explorer.replay-from` answers every request from an earlier run's
  observations, with no network; planning, extraction, checking and the report
  run unchanged, so a failing chain reproduces at a desk. Unrecorded requests,
  recorded failures and bodies truncated at `max-body-chars` come back as
  failed calls with the reason; replaying into the recording's own directory
  is refused. Stateful exploration (all four steps) is complete.
- `module-api-explorer`: stateful exploration, step 3 — write chains. With
  both write keys on, `forge.api-explorer.bodies.<operationId>` templates are
  the only source of request bodies (`{{unique}}` becomes a fresh token per
  request), so a `POST` can create the object whose id later `GET`/`DELETE`
  calls use. `DELETE` now always runs after every other operation. A run that
  sent write requests states how many, and to which base URL, at the top of
  the report. Templates and rules change nothing without both write keys.
- `module-api-explorer`: stateful exploration, step 2. Every run reads the
  document for likely producer/consumer links — the list endpoint one level
  above an item endpoint, or a response property named like the parameter —
  and proposes them as ready-to-paste `extract` rules in the report
  (*Proposed extraction rules*, also in `report.json`) with the reason for
  each. Parameters already configured or fed get none, and proposals are never
  applied on their own.
- `module-api-explorer`: stateful exploration, step 1. `forge.api-explorer.extract`
  rules take a value from one operation's 2xx JSON response and feed it to
  later calls (`operationId.parameter`, or a bare parameter name for every
  operation); producers run before their consumers. An extracted value beats
  the document's example, a configured value still wins. The report gets a
  *Value extraction* table with one status per rule and never the value.
  Read-only chains (list, then item) need no write method.
- `core`: `JsonPath` reads root arrays (`$[0].id`).
- `module-db`: `SchemaValidator.indexDrift` and `foreignKeyDrift`, both part of
  `schemaDrift`, report constraints the mapping declares that the service's
  database no longer has. Indexes (`@Table(indexes)`, `uniqueConstraints`,
  `@Column(unique)`) are matched by columns, never by generated names; a
  declared unique index must stay unique. Foreign keys declared with
  `@ForeignKey` must exist and point at the mapped table and column;
  `forge.db.schema-foreign-keys: all` expects one behind every join column.
  Extra database constraints are never reported. Proven on H2 and PostgreSQL.
- `module-db`: `SchemaValidator` understands JPA inheritance. Fields of
  `@MappedSuperclass` ancestors are validated on the entity's table (a
  class-level `@AttributeOverride` renames them), and entity hierarchies follow
  the root's strategy: `SINGLE_TABLE` validates the whole chain plus the
  discriminator column against the root table, `JOINED` the entity's own
  fields plus the primary-key join column, `TABLE_PER_CLASS` the whole chain
  against the entity's own table. Previously inherited fields were ignored and
  a single-table subclass was checked against a table that does not exist.
- `module-db`: implicit names follow a `SchemaNamingStrategy` —
  `forge.db.schema-naming: snake-case` (default, unchanged) or `as-is`, or a
  bean; `@Entity(name)` now names the default table.
- `module-data`: `forge.data.pool.preload` stocks prepared variants when the
  context starts (a failing provider fails the context), with optional
  background refill (`refill-below`) on one daemon thread whose failures are
  counted, never thrown into tests. `PreparedDataPool.stats()` reports hits,
  cold misses, prepared, exhausted and refill failures per variant; with
  `module-reporting` enabled they are published as `pool-stats.json`.
- `module-mock`: requests without a body can now be scoped. Set exactly one of
  `forge.mock.scope-header`, `scope-query-param` or `scope-cookie` and it
  replaces the body JSON path as the scope marker; configuring two fails at
  startup. One `ScopeMarker` drives stub matching, the request journal and the
  unmatched-request diagnostics, which now record the marker kind and name.
  `module-http` writes `forge.http.scope.header` from `forge.mock.scope-header`
  when unset, so the header is configured once — previously the HTTP header
  mode had nothing to match it on the mock side.
- `MockScope.requests()`, `requests(pattern)` and `verify(count, pattern)`:
  assertions on the scenario's own traffic in the shared WireMock journal;
  other scenarios' requests never count.
- `module-auth` (experimental): tests declare the role they act as with
  `@AsUser("admin")` (or `AuthContext.runAs` for a block) and the `ApiClient`
  sends that role's token. Roles are configured under `forge.auth.roles` as
  `static`, OAuth2 `client-credentials` / `password`, or `local-jwt` (HS256
  signed with a shared test secret, no identity provider). Tokens are cached
  per role, refreshed shortly before expiry, fetched once under parallel load,
  and never printed. Every misconfigured role is reported at startup. A header
  the test sets itself is never replaced. No new third-party dependency.
- Unified run index now covers every module that writes diagnostics.
  `module-api-discovery`, `module-api-explorer`, `module-contract-monitor` and
  `module-api-fuzz` register their run reports, `module-web-playwright` and
  `module-mobile-appium` register failure screenshots, traces and page sources.
  Each runner keeps writing to its configured `output-dir`; nothing moves. A
  new best-effort `ArtifactSink.registerFile(...)` in `core` does the
  registration and records a relative path as `location` metadata, because the
  manifest keeps only the file name for anything outside the run root.
  Existing constructors are kept and fall back to `ArtifactSink.NO_OP`.
- `architecture-tests`: an unpublished ArchUnit module that turns the
  conventions in `AGENTS.md` into failing tests — `core` isolation, an explicit
  allowlist of module-to-module dependencies, no sleeps/Lombok/standard
  streams/`java.util.logging`/field injection, and the shape every module must
  ship (auto-configuration listed in `AutoConfiguration.imports`, `*Properties`
  records under `forge.*`, README, `AGENTS.md`, and an own or example test).
  The files these rules read are declared as task inputs, so deleting a README
  or a test reruns the check instead of reporting it up to date.
- A per-module `AGENTS.md` for every module (scope, allowed dependencies,
  invariants, tests) and tool-neutral agent playbooks in `docs/agents/` for
  adding, changing and reviewing a module.
- `module-web`: offline tests for the auto-configuration and property defaults;
  previously the module had no test of any kind.
- Own unit tests for every module that had none: `module-http` (redaction,
  scope path writing, property defaults and service resolution, filter chain
  wiring and order), `module-data` (uniqueness under parallel load, generators,
  template recursion/cycles/depth, prepared pool including exactly-once
  hand-out across threads), `module-contract` (field DSL and JSON Schema
  ordering/fail-fast), `module-kafka` (buffer trimming and ordering, probe
  criteria, await polling), `module-state` (target tag resolution, recipe
  execution and error wrapping), `module-web-playwright` (stays inert without
  `enabled=true`).
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
- `module-data`: a pool variant is now a type plus a tag *set* —
  `["vip","eu"]` and `["eu","vip"]` share stock instead of splitting it;
  listeners receive the sorted set, providers still the caller's order.
- `module-data`: `PoolEventListener.onExhausted` fires once per drain (on the
  first cold miss) instead of on every later miss; a preload or refill
  re-arms it.
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
- Default Spring Boot baseline moved to 3.5.x for a calmer enterprise adoption
  path while keeping Java 21 LTS and Gradle 9.x.
- Default toolchain moved to Java 21 LTS for broader enterprise adoption;
  `ScenarioContext.runScoped(...)` no longer depends on preview JDK APIs.
- Repository hygiene: the personal employer-project analysis note was removed
  from the tree and purged from history; the roadmap hygiene section no longer
  lists concrete previous-employer terms.

### Fixed
- `module-http`: authentication guidance points to the available `module-auth`
  instead of claiming that a dedicated module does not exist.
- `module-api-explorer`: replay reports state `write requests sent: 0` and no
  longer claim that replayed POST/DELETE observations changed environment data.
- `module-db-contract`: report and snapshot entries now retain project-relative
  `location` metadata when their output directory is outside the run root.
- `module-http`: a `Redactor` built directly with mixed-case names
  (`X-Api-Key`, `clientSecret`) did not mask them. Lookups lowercase the
  incoming name, but only `HttpProperties` lowercased the configured side, so
  a project replacing the `@ConditionalOnMissingBean` redactor bean leaked the
  credential it meant to hide. The constructor now normalizes the names itself.
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
- `module-mobile-appium`: failure-artifact capture no longer aborts the page
  source when the screenshot fails — each artifact is captured independently
  and errors are surfaced as one (suppressed) exception.

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
[1.2.0]: https://github.com/MickeyOrlov/TestForge/releases/tag/v1.2.0
[1.1.0]: https://keepachangelog.com/
[1.0.0]: https://keepachangelog.com/
