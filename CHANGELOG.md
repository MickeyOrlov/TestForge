# Changelog

All notable changes to this template are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); the project uses
semantic versioning for its git tags.

## [Unreleased]

### Changed
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
