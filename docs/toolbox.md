# The toolbox

What is in the box, for which situation, and which compartments are still
empty. The principle is in the README ("A toolbox, not a home-made hammer");
the rule for adding to it is in [AGENTS.md](../AGENTS.md) ("Toolbox rule").

Assessed in November 2026 from each module's README and build file. A change
that adds, replaces or removes a tool updates its row here in the same commit.

**Status**

| Status | Meaning |
|---|---|
| adapter | a proven tool with a TestForge handle — the shape the box wants |
| reader | TestForge reads the tool's report; the project runs the tool |
| glue | TestForge's own code for a job no tool does — wiring, policy, evidence |
| alongside | used next to TestForge, deliberately not wrapped |
| own — review | TestForge's own code where a proven tool exists; needs a decision |
| empty | a situation with no tool in the box yet |

## Every day

| Situation | Tool | In TestForge | Status |
|---|---|---|---|
| Call an HTTP API with environments, correlation, redacted logs | REST Assured | `module-http` | adapter |
| Act as a role without writing the login | REST Assured, OAuth2 / JWT | `module-auth` (token cache, `@AsUser`) | glue |
| Wait for something asynchronous, never sleep | Awaitility | `core` `Waiter`, `module-db` `DbWaiter` | adapter |
| Stub a downstream service per scenario, in parallel | WireMock | `module-mock` | adapter |
| Unique and templated test data | — | `module-data` | glue |
| Assert what the service persisted; capture its SQL | datasource-proxy | `module-db` | adapter |
| Wait for an event on Kafka / RabbitMQ; publish one | Spring Kafka, Spring AMQP | `module-kafka`, `module-rabbitmq` | adapter |
| Drive a browser | Playwright | `module-web-playwright` | adapter |
| Warm slow pages before a UI suite | Playwright | `module-web` | glue |
| Drive a mobile app on a device matrix | Appium | `module-mobile-appium` | adapter |
| Write acceptance tests as Gherkin | Cucumber | `module-gherkin` | adapter |
| Set up a complex business state step by step | — | `module-flow`, `module-state` | glue |
| Keep a run's evidence in one place; readable report | Allure (optional) | `module-reporting` (run index, `manifest.json`, `summary.md`) | glue |
| Disposable database or broker in CI | Testcontainers | `example-tests` (`PostgresSchemaValidationIT`) | alongside |

## Contracts and schemas

| Situation | Tool | In TestForge | Status |
|---|---|---|---|
| Know what an OpenAPI document offers; catch its drift | swagger-parser | `module-api-discovery` | adapter |
| Check a JSON payload against a contract | networknt json-schema-validator | `module-contract` | adapter |
| Watch Kafka payloads drift from their contract | — | `module-contract-monitor` | glue |
| Check every API response against its document while tests run | — | `module-api-explorer` contract guard | glue |
| Keep WireMock stubs honest to the downstream's document | — | `module-api-explorer` stub contract | glue |
| Tell whether a schema change breaks a database consumer | SchemaCrawler | `module-db-contract` | adapter |
| Verified provider / consumer contract between teams | Pact, Spring Cloud Contract | — (README "TestForge + Testcontainers + Pact") | alongside |
| Typed Java clients and records from OpenAPI | OpenAPI Generator (`java`, `rest-assured` library) | `module-api-codegen` (own generator) | own — review |

## Exploring, breaking, attacking

| Situation | Tool | In TestForge | Status |
|---|---|---|---|
| What does the API actually do, operation by operation | EvoMaster, Schemathesis | `module-api-explorer` (GET walker) | own — review |
| Which role gets into which operation | — | `module-api-explorer` access matrix | glue |
| Schema-aware fuzzing | Schemathesis | `module-api-fuzz` | adapter |
| Black-box fuzzing with request chains, generated tests | EvoMaster | `module-quality` reads its WFC `report.json` | reader |
| Security issues visible in responses, no extra traffic | — | `module-security` passive checks | glue |
| Active web / API security scan | OWASP ZAP | `module-security` (scoped ZAP, Docker) | adapter |
| Secrets leaking into test artifacts | gitleaks, TruffleHog | `module-security` own pre-upload scan | own — review |
| Vulnerable dependencies; static analysis of the product | Trivy, Grype, Semgrep, CodeQL (SARIF) | — | empty |
| Network faults, latency, a dependency going down | Toxiproxy | — | empty |

## Performance

| Situation | Tool | In TestForge | Status |
|---|---|---|---|
| Load an API with native thresholds | k6 | `module-load` | adapter |
| Generate a k6 client from OpenAPI | Grafana openapi-to-k6 | `tools/api-load` | adapter |
| Watch a load run live | Prometheus, Grafana | `infra/load` (pinned compose stack) | alongside |
| A test that got slower than its own history | — | `module-quality` `efficiency` | glue |
| Front-end performance budgets | Lighthouse CI | — | empty |

## Judging the run

| Situation | Tool | In TestForge | Status |
|---|---|---|---|
| Is this build worse than the accepted one; can it ship | — | `module-quality` (`testforge-quality`) | glue |
| Would the tests notice a change in the code | PIT | `module-quality` `mutation` | reader |
| Are the lines this change touched covered | diff-cover | `module-quality` `diff-coverage` | reader |
| Flaky tests, quarantine with an expiry | — | `module-quality` `stability`, `accepted @fingerprint` | glue |
| Group failures into categories | Allure categories | — (Allure's job) | alongside |

`module-quality` is TestForge's own code on purpose: the market review
([research/quality-oss-analysis.md](research/quality-oss-analysis.md),
[research/quality-dogfood-petclinic.md](research/quality-dogfood-petclinic.md))
found no server-less judge with a baseline, fingerprint quarantine and
TestForge's runtime signals. Coverage and mutation parsing overlap with
[uhafner/quality-monitor](https://github.com/uhafner/quality-monitor); worth
checking whether to read its model instead of keeping own parsers.

## Rare compartments — still empty

The pipe wrenches: needed rarely, needed at once when they are. Each would be
an opt-in adapter or reader; none is planned until a project needs it.

| Situation | Proven tool |
|---|---|
| Accessibility of a page | axe-core (with Playwright) |
| Visual regression of a page | Playwright screenshots |
| Mail a service sends | Mailpit, GreenMail |
| AWS services a product depends on | LocalStack |
| Database migrations apply and roll back | Flyway, Liquibase |
| Realistic fake data | Datafaker |
| Vulnerable dependencies, static analysis | Trivy, Grype, Semgrep, CodeQL — one SARIF reader covers them |
| Network faults | Toxiproxy (Testcontainers module) |
| Front-end performance | Lighthouse CI |

## To decide

The `own — review` rows, one decision record each (keep, wrap or replace),
in the manner of [api-fuzz-prototype-analysis.md](api-fuzz-prototype-analysis.md):

1. **`module-api-explorer` walker.** EvoMaster found 500s on 41 petclinic
   operations where the walker found one (it covers GET only). Its contract
   guard, access matrix and stub contract have no off-the-shelf equivalent and
   stay; the walking itself is the candidate to hand over.
2. **`module-api-codegen`.** OpenAPI Generator's `java` generator with the
   `rest-assured` library, plus templates for TestForge's `ApiClient`, may do
   what the own generator does.
3. **`module-security` artifact secret scan.** gitleaks and TruffleHog are the
   established scanners; the own scan knows TestForge's configured secrets
   (`forge.auth.roles.*`), which they do not — a reason to keep, or to combine.
