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
module-data/   RunUniqueValues, TemplateRenderer for generated test data
module-db/     DbWaiter, SqlLoggingDataSourcePostProcessor, SchemaValidator
module-flow/   FlowRunner — deterministic state-machine paths with guardrails
module-state/  StateRecipe — reusable business state setup feeding @Prepared
module-kafka/  KafkaProbe — topic buffer/search; composes with module-contract
module-mock/   ScopedMockClient/MockScope — per-scenario stubs on shared WireMock
module-reporting/ ResourceUsageMonitor for CI diagnostics
module-web/    PrewarmRunner — warm key pages once per suite
module-web-playwright/ Playwright lifecycle + Page fixture + failure artifacts
module-mobile-appium/  Appium lifecycle, device matrix, failure artifacts
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
4. **module-db**: add JPA entities + Spring Data repositories for the service
   tables tests need to assert on (separate Gradle module per service DB if
   there are many). Write one `SchemaValidator` test per entity and schedule
   them in CI — they catch service migrations that silently break mappings.
   If the product publishes client/DTO artifacts (a rest-client module,
   generated OpenAPI models), DEPEND on them instead of duplicating classes:
   the compiler then catches DTO drift, `SchemaValidator` catches DB drift,
   module-contract catches runtime payload drift — three independent layers.
   Enable `forge.db.repository-polling.enabled` only when you want `waitBy...`
   default repository methods to poll matching `findBy...` queries.
5. **module-contract**: encode external API/event/file payloads as
   `MessageContract`s and validate them in scheduled checks. This is the
   neutral core for Kafka/topic drift monitoring: the consumer adapter pulls
   payloads, this module decides whether the shape changed.
6. **module-contract-monitor**: for scheduled Kafka drift checks, register
   `ContractMonitorCase` beans and run `ContractMonitorRunner.assertHealthy()`
   from a JUnit job. Enable Kafka topics only in the environment profile that
   has broker access. Keep baseline artifacts as CI artifacts or explicit
   inputs; do not auto-rewrite `src/test/resources`. Shape snapshots must
   contain types only, never real payload values.
7. **module-data**: use `RunUniqueValues` around domain generators and
   `TemplateRenderer` for payloads or tables that reference scenario values.
   For expensive domain states implement `PreparedDataProvider<T>` (drive the
   product API, typically a module-flow run inside `prepare(tags)`), then
   inject objects into tests with `@Prepared` + `PreparedParameterResolver`.
   Stock hot variants with `pool.preload(...)` in a suite hook; wire refill
   or metrics through `PoolEventListener`.
8. **module-flow**: use `FlowRunner` for long setup paths where a scenario must
   reach a deep state through deterministic transitions. Keep steps small and
   idempotent; the runner should make failures readable by showing the path.
9. **module-state**: for reusable business setup, implement
   `StateRecipe<T, S>` and expose it through `StatePreparedDataProvider`. Tests
   then ask for `@Prepared(tags = "approved")` or
   `@Prepared(tags = {"state:approved", "tenant:demo"})` instead of repeating
   setup calls. Recipes should use product/test-support APIs and `FlowRunner`;
   direct DB writes are an explicit project decision, not the default.
10. **module-kafka**: enable `forge.kafka.enabled` only in profiles that have
   broker access. Use `KafkaProbe` to find messages by topic/key/header/JSON
   path; shape checks compose with `module-contract` (await the message, then
   `assertValid` its value) — never reintroduce a hard dependency between the
   two modules.
11. **module-reporting**: enable `forge.reporting.resource-monitor.enabled` in
   CI profiles when you need JVM memory/CPU diagnostics for slow or flaky runs.
12. **module-web**: list the 2–4 heaviest pages of the system under test in
   `forge.prewarm.urls` for the CI profile.
13. **module-mobile-appium**: put real devices in explicit mobile profiles,
   never in the default build. `forge.mobile.appium.enabled=true` only creates
   beans; sessions open lazily when a test requests `AppiumSession` or
   `AppiumDriver`. Use `devices.<id>` + `@MobileDevice("id")` for matrix
   selection, keep local node startup opt-in with `node.auto-start=true`, and
   upload `build/appium-artifacts` from mobile CI jobs. Screen objects and
   provider-specific clients stay in the adapted project.
14. **Delete what is not needed.** Unused modules: remove the directory and its
   line in settings.gradle. The build must stay green after deletion.
15. **Client/DTO artifacts (drift layer 3).** When the product publishes a
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
- `SqlLoggingDataSourcePostProcessor` wraps the DataSource bean, so beans
  expecting the concrete type (e.g. `HikariDataSource`) will break under it.
  Configure the pool through `spring.datasource.*` properties instead of
  casting the bean.
- Prewarm downloads a Chromium on first run (`playwright install chromium`
  in CI images avoids the per-run download).
- `module-contract` is payload *shape* validation, not consumer-driven
  contract testing. Its rule DSL is deliberately minimal: when you need
  patterns, enums or ranges, swap the internals for JSON Schema
  (`com.networknt:json-schema-validator`) behind the same `MessageContract`
  API — do not grow the homemade DSL.
