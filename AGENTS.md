# TestForge — agent guide

TestForge is a **template** test framework: it gets cloned into an organization and
adapted. If you are reading this inside a freshly cloned repo, your job is
probably one of: (a) adapt the template to a concrete project/system,
(b) add a module, (c) write tests on top of it.

## Map

```
core/          ScenarioContext (typed thread-local), Waiter (polling, no sleeps)
module-contract/ JSON message contracts for API/queue/file drift checks
module-contract-monitor/ Kafka drift monitor: contract validation + shape diff report
module-api-discovery/ OpenAPI catalog + schema shape snapshots
module-api-codegen/ OpenAPI-first Java records + typed ApiClient skeletons
module-api-explorer/ runs an OpenAPI document against a live API; runtime contract report
module-data/   RunUniqueValues, TemplateRenderer for generated test data
module-db/     DbWaiter, SqlLoggingDataSourcePostProcessor, SchemaValidator
module-db-contract/ schema snapshot, bounded diff, compatibility policy, CI gate
module-flow/   FlowRunner — deterministic state-machine paths with guardrails
module-state/  StateRecipe — reusable business state setup feeding @Prepared
module-http/   ApiClient — REST Assured spec with scope/correlation/logging filters
module-kafka/  KafkaProbe — topic buffer/search; composes with module-contract
module-mock/   ScopedMockClient/MockScope — per-scenario stubs on shared WireMock
module-reporting/ run-scoped artifact index (manifest/summary), resource monitor, optional Allure attachments
module-web/    PrewarmRunner — warm key pages once per suite
module-web-playwright/ Playwright lifecycle + Page fixture + failure artifacts
module-mobile-appium/  Appium lifecycle, device matrix, failure artifacts
module-api-fuzz/ delegating schema-aware fuzzing to Schemathesis
example-tests/ reference suite, runs offline (embedded WireMock + H2)
```

All modules are Spring Boot auto-configurations (see
`src/main/resources/META-INF/spring/...AutoConfiguration.imports` in each).
Config lives under the `forge.*` prefix; properties classes are records named
`*Properties`. One Spring profile per test environment
(`application-<environment>.yml`).

## Build & verify

```bash
./gradlew build                  # full check: compile + example suite
./gradlew :example-tests:test    # just the reference tests
```

Definition of done for any change: `./gradlew build` is green. The example
suite is the living documentation — when you change a module's behaviour,
update its example test in the same commit.

Production modules can also be consumed as Maven libraries. Run
`./gradlew publishTestForgeLibraries` and then
`./gradlew -p smoke-tests/library-consumer test` to verify the published JAR,
POM, transitive dependencies, and Spring Boot auto-configuration from an
independent build. `example-tests` is living integration documentation inside
the TestForge source tree; `smoke-tests/library-consumer` validates external
Maven consumption without project dependencies. They are complementary and
must not replace each other. `example-tests` is never published.

## Adaptation playbook (new project)

1. **Rename**: group `io.testforge` and packages → target namespace;
   `rootProject.name` in settings.gradle.
2. **Environments**: create `application-<environment>.yml` per environment in the test
   module's resources. Never commit secrets — reference env vars or a secret
   manager.
3. **module-mock**: find the field that ties a downstream request to one test
   scenario (for example, a test scope, correlation id, or request id that the
   system under test echoes into mock-bound calls). Point
   `forge.mock.scope-json-path` at it. This is THE critical adaptation step — without a correct scope field,
   parallel tests will fight over shared stubs.
4. **module-http**: set `forge.http.base-url` per environment profile (plus
   `services.<id>` when tests span several backends) and inject `ApiClient`
   instead of building specifications in test code. Leave
   `forge.http.scope.json-path` unset — it follows `forge.mock.scope-json-path`
   so the correlation field is configured once. Extend
   `forge.http.logging.redact-*` with the product's own credential field names
   BEFORE the first CI run uploads a log. Authentication goes in as an
   `ApiRequestCustomizer` (per specification) or a REST Assured `Filter` bean
   (per request); any `Filter` bean in the context is applied to every request.
   Turn `retry.enabled` on only where infrastructure noise is real.
5. **module-db**: add JPA entities + Spring Data repositories for the service
   tables tests need to assert on (separate Gradle module per service DB if
   there are many). Write one `SchemaValidator` test per entity and schedule
   them in CI — they catch service migrations that silently break mappings.
   If the product publishes client/DTO artifacts (a rest-client module,
   generated OpenAPI models), DEPEND on them instead of duplicating classes:
   the compiler then catches DTO drift, `SchemaValidator` catches DB drift,
   module-contract catches runtime payload drift — three independent layers.
   Enable `forge.db.repository-polling.enabled` only when you want `waitBy...`
   default repository methods to poll matching `findBy...` queries.
6. **module-db-contract**: capture the schema of the database tests depend on
   with `dbContractRunner.writeBaseline()`, commit or archive that snapshot, and
   run `assertCompatible()` from a scheduled or review job — never the default
   build, which has no database to inspect. Set `forge.db-contract.schema` and
   exclude migration bookkeeping tables (`flyway_schema_history`,
   `databasechangelog`) through `exclude-tables`. That job must also set
   `forge.db-contract.enabled=true`: `assertCompatible()` fails closed while the
   check is disabled rather than passing without inspecting anything, so a job
   that forgets it is told, not silently green. `run()` is the reporting-only
   entry point and stays usable either way. Leave `fail-on.risky` and
   `fail-on.unknown` at `false` until the first reports have been read; turn
   `risky` on once the team trusts the baseline. Promoting a new baseline is
   always an explicit `writeBaseline()` call in a reviewed change, never a side
   effect of the check. Projects with their own rules replace the
   `DbCompatibilityPolicy` bean instead of editing the module.
7. **module-contract**: encode external API/event/file payloads as
   `MessageContract`s and validate them in scheduled checks. This is the
   neutral core for Kafka/topic drift monitoring: the consumer adapter pulls
   payloads, this module decides whether the shape changed.
8. **module-contract-monitor**: for scheduled Kafka drift checks, register
   `ContractMonitorCase` beans and run `ContractMonitorRunner.assertHealthy()`
   from a JUnit job. Enable Kafka topics only in the environment profile that
   has broker access. Keep baseline artifacts as CI artifacts or explicit
   inputs; do not auto-rewrite `src/test/resources`. Shape snapshots must
   contain types only, never real payload values.
9. **module-api-discovery**: point `forge.api-discovery.specs.<id>.location`
   at local OpenAPI files in the default build and run
   `ApiDiscoveryRunner.assertHealthy()` from a scheduled or review job. URL
   specs belong only in explicit environment profiles. Store catalog/shape
   baselines as CI artifacts or checked project inputs; snapshots contain
   schema shape only, never example values.
10. **module-api-codegen**: reuse the `forge.api-discovery.specs` registry and
   run `ApiCodegenRunner.assertGenerated()` only when generated API sources
   are requested. Keep the default output under `build/generated/testforge`;
   never point generation at a hand-maintained source directory. Generated
   records and clients are transport scaffolding, not business tests. V1 does
   not attach its output to a Gradle source set automatically.
11. **module-api-explorer**: reuse the same `forge.api-discovery.specs`
   registry and set `forge.http.base-url` for the environment. Leave
   `forge.api-explorer.methods` at its default — GET/HEAD/OPTIONS need no
   opt-in, and anything else needs BOTH the method listed and
   `allow-unsafe-methods=true`. Never enable write methods against a shared
   environment. Run `ApiExplorerRunner.assertHealthy()` from an environment
   profile job, never the default build; `fail-on.contract-mismatch` stays
   false until the first report has been reviewed. Feed missing identifiers
   through `forge.api-explorer.parameters` — the report prints the block to
   paste. Redaction follows `forge.http.logging.redact-*`; extend it before the
   first run uploads artifacts.
12. **module-api-fuzz**: reuse `forge.api-discovery.specs`, leave methods at the default, never enable write methods against a shared environment, install Schemathesis separately, run it from an environment profile job and never the default build.
13. **module-data**: use `RunUniqueValues` around domain generators and
   `TemplateRenderer` for payloads or tables that reference scenario values.
   For expensive domain states implement `PreparedDataProvider<T>` (drive the
   product API, typically a module-flow run inside `prepare(tags)`), then
   inject objects into tests with `@Prepared` + `PreparedParameterResolver`.
   Stock hot variants with `pool.preload(...)` in a suite hook; wire refill
   or metrics through `PoolEventListener`.
14. **module-flow**: use `FlowRunner` for long setup paths where a scenario must
   reach a deep state through deterministic transitions. Keep steps small and
   idempotent; the runner should make failures readable by showing the path.
15. **module-state**: for reusable business setup, implement
   `StateRecipe<T, S>` and expose it through `StatePreparedDataProvider`. Tests
   then ask for `@Prepared(tags = "approved")` or
   `@Prepared(tags = {"state:approved", "tenant:demo"})` instead of repeating
   setup calls. Recipes should use product/test-support APIs and `FlowRunner`;
   direct DB writes are an explicit project decision, not the default.
16. **module-kafka**: enable `forge.kafka.enabled` only in profiles that have
   broker access. Use `KafkaProbe` to find messages by topic/key/header/JSON
   path; shape checks compose with `module-contract` (await the message, then
   `assertValid` its value) — never reintroduce a hard dependency between the
   two modules.
17. **module-reporting**: enable `forge.reporting.artifacts.enabled=true` in
   CI profiles to collect run diagnostics into a unified directory
   (`build/testforge-artifacts/<run-id>/`) with a `manifest.json` and `summary.md`
   index. Producing modules publish through the `ArtifactSink` seam in `core`.
   Turn `forge.reporting.resource-monitor.enabled=true` on for JVM memory/CPU
   sampling.
18. **module-web**: list the 2–4 heaviest pages of the system under test in
   `forge.prewarm.urls` for the CI profile.
19. **module-mobile-appium**: put real devices in explicit mobile profiles,
   never in the default build. `forge.mobile.appium.enabled=true` only creates
   beans; sessions open lazily when a test requests `AppiumSession` or
   `AppiumDriver`. Use `devices.<id>` + `@MobileDevice("id")` for matrix
   selection, keep local node startup opt-in with `node.auto-start=true`, and
   upload `build/appium-artifacts` from mobile CI jobs. Screen objects and
   provider-specific clients stay in the adapted project.
20. **Delete what is not needed.** Unused modules: remove the directory and its
   line in settings.gradle. The build must stay green after deletion.
21. **Client/DTO artifacts (drift layer 3).** When the product publishes a
   versioned client or DTO module (OpenAPI-generated stubs, shared event
   types), make the test module depend on it instead of duplicating JSON
   shapes in test code. Keep `SchemaValidator` (DB mappings) and
   `module-contract` (payload shape on the wire) as complementary checks —
   not replacements for a shared artifact when one exists.

Future modules and staged work live in [docs/ROADMAP.md](docs/ROADMAP.md).

## Conventions

- Java 21 LTS toolchain (auto-provisioned via foojay resolver), Spring Boot 3.5.x,
  Gradle 9.x. No Lombok in template code (adapters may add it).
- When ordering against optional Spring Boot auto-configurations from a module
  that does not depend on that technology, use the string-based `afterName`
  attribute, not a class reference.
- No `Thread.sleep` anywhere — use `Waiter`/`DbWaiter`. If you believe you
  need a sleep, you need a polling condition you have not written yet.
- Test data must be unique per run (UUID/timestamp suffixes), never shared
  fixtures mutated in place.
- Every new module ships: auto-configuration + `*Properties` record +
  README.md + at least one example test in example-tests.
- Mock stubs: defaults are low priority (10+), scoped stubs are priority 1
  and ALWAYS carry the scope matcher. Never register an unscoped catch-all
  from inside a test.

## Known sharp edges

- `SchemaValidator` resolves names reflectively (camelCase→snake_case,
  `@Column`/`@JoinColumn`/`@Embedded` with `@AttributeOverride`). It does not
  understand inheritance or custom naming strategies — extend it before
  relying on it for entities that use those.
- `module-db-contract` matches tables, columns, indexes and foreign keys by
  name, so a rename reads as a removal plus an addition. It stores identifiers
  exactly as the vendor reports them (PostgreSQL lower, H2 upper), which makes
  snapshots comparable within one database lineage but not across vendors. It
  stays on generic JDBC retrieval on purpose: letting SchemaCrawler match a
  known server type makes it demand that vendor's plugin on the classpath.
- `SqlLoggingDataSourcePostProcessor` wraps the DataSource bean, so beans
  expecting the concrete type (e.g. `HikariDataSource`) will break under it.
  Configure the pool through `spring.datasource.*` properties instead of
  casting the bean.
- Prewarm downloads a Chromium on first run (`playwright install chromium`
  in CI images avoids the per-run download).
- `module-api-explorer` sends real traffic. It is inert unless
  `forge.api-explorer.enabled=true`, and write methods need two keys, not one.
  Ant `exclude-paths` of the form `/tasks/**` also match `/tasks` itself.
- - `module-http` scope injection rewrites JSON bodies sent as a string or byte
  array. REST Assured serializes object/POJO bodies *after* filters run, so
  those requests need `forge.http.scope.header` instead of the JSON path.
- A `RequestSpecification` built by `RequestSpecBuilder` cannot be sent on its
  own (no response specification attached); `ApiClient` merges it into
  `RestAssured.given()`.
- `module-contract` is payload *shape* validation, not consumer-driven
  contract testing. Its rule DSL is deliberately minimal: when you need
  patterns, enums or ranges, swap the internals for JSON Schema
  (`com.networknt:json-schema-validator`) behind the same `MessageContract`
  API — do not grow the homemade DSL.
- `module-api-codegen` maps `oneOf`/`anyOf` to `Object` and enum schemas to
  their wire scalar type in v1. Generated sources are a build artifact and are
  not compiled in the same test phase that creates them.
- `module-api-fuzz`: `--include-method` alone does not stop the coverage phase from emitting unspecified methods. TestForge also generates a config file to enforce the safety policy.
- Artifact reporting (`forge.reporting.artifacts.enabled`) and resource usage monitoring (`forge.reporting.resource-monitor.enabled`) are disabled by default (`false`); set `forge.reporting.artifacts.enabled=true` in CI profiles to generate run manifests and summaries. When `module-reporting` is absent or disabled, `ArtifactSink.NO_OP` is active, so producer calls succeed silently without collecting artifacts into a run directory.
