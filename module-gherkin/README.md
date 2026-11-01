# module-gherkin

Optional. For teams that write acceptance tests as Gherkin feature files:
ready-made Cucumber steps over the TestForge modules, with the guarantees a
TestForge JUnit test has — a scope and correlation id per scenario, waits
instead of sleeps, failures that name the request. TestForge stays
JUnit-first; a project that does not use Gherkin deletes this module.

## What's inside

- **`io.testforge.gherkin.glue.ScenarioHooks`** — every scenario starts with an
  empty `ScenarioContext`, a scope of its own (`bdd-1a2b3c4d`) and a
  correlation id, both logged to the scenario.
- **`ScenarioVariables`** — `I remember the response field "$.id" as
  "orderId"`, then `"/orders/${orderId}"` in any later step.
- **`io.testforge.gherkin.http.HttpSteps`** — requests through `ApiClient`
  (base URL, scope and correlation headers, auth, redacted logging):

| Step | |
|---|---|
| `Given the request header "X-Tenant" is "t-1"` | for every later request of the scenario |
| `When I send a GET request to "/orders/${orderId}"` | any method |
| `When I send a POST request to "/orders" with body:` + doc string | JSON bodies get `Content-Type: application/json` |
| `Then the response status is 201` | |
| `Then the response field "$.status" is "NEW"` | JSON path, `${name}` allowed |
| `Then the response field "$.note" contains "late"` | |
| `Then I remember the response field "$.id" as "orderId"` | |

- **`io.testforge.gherkin.glue.VariableSteps`** — values for this run:

| Step | |
|---|---|
| `Given I remember a unique value as "orderId"` | `orderId-1a2b3c4d5e6f`, unique per run |
| `Given I remember "${orderId}-line-1" as "lineId"` | |
| `Then the remembered "status" is "PAID"` | for a value a step remembered |

- **`io.testforge.gherkin.kafka.KafkaSteps`** — over `KafkaProbe` / `KafkaPublisher`:

| Step | |
|---|---|
| `When I publish a Kafka message to "orders.commands" with key "${orderId}":` + doc string | with the scenario's scope and correlation headers |
| `Then a Kafka message arrives on "orders.events" with key "${orderId}"` | |
| `Then a Kafka message arrives on "orders.events" where "$.orderId" is "${orderId}"` | |
| `Then exactly one Kafka message arrives on "orders.events" where …` | watches `forge.gherkin.settle` for a duplicate |
| `Then no Kafka message arrives on "orders.events" where …` | watches `forge.gherkin.settle` |
| `Then I remember the Kafka message field "$.paymentId" as "paymentId"` | from the last awaited message |

- **`io.testforge.gherkin.rabbitmq.RabbitSteps`** — the same over `RabbitProbe` /
  `RabbitPublisher`: `I publish a RabbitMQ message to exchange "orders" with
  routing key "order.pay":`, `a RabbitMQ message arrives on exchange
  "payments" with routing key "payment.captured"` / `where "$.orderId" is …`,
  `exactly one …`, `no RabbitMQ message arrives on exchange …`,
  `I remember the RabbitMQ message field … as …`.

A timeout names the closest messages by position and failed criteria, as
the probes do; no step prints a message body.

**Whose message is it?** Without more, `a Kafka message arrives on
"orders.events" where "$.status" is "PAID"` is satisfied by *any* scenario's
PAID event. Either filter on a run-unique value (`"$.orderId" is
"${orderId}"`), or set `forge.gherkin.scope-messages: true`: message steps
then match only messages whose scope header (`forge.kafka.publish.scope-header`
/ `forge.rabbitmq.publish.scope-header`) carries this scenario's scope. That
needs a system that copies the header from the command into its events.

When a scenario fails, `KafkaScenarioDiagnostics` / `RabbitScenarioDiagnostics`
(in the same glue packages) attach its traffic to the scenario and write it
to the run index — the report `KafkaDiagnosticsExtension` writes for a JUnit
test, scoped and without bodies by default.

- **`io.testforge.gherkin.db.DbSteps`** — over `DbState`:

| Step | |
|---|---|
| `Then table "orders" has a row where "id" is "${orderId}"` | waits for it |
| `Then table "orders" has a row where "id" is "…" and "status" is "PAID"` | |
| `Then in table "orders" the row where "id" is "${orderId}" has "status" set to "PAID"` | waits for it |
| `Then table "orders" has no row where "id" is "${orderId}-copy"` | watches `forge.gherkin.settle` |
| `Given I watch table "orders" keyed by "id" where "customer_id" is "${customerId}"` | a snapshot of the scenario's rows |
| `Then table "orders" has 1 added, 0 changed and 0 removed rows` | against the watched snapshot, the diff in the failure |
| `Then table "orders" is unchanged` | |

  Values compare with the column's text (`CAST(… AS VARCHAR)`, H2 and
  PostgreSQL), so `"10.00"` matches a `NUMERIC(10,2)` the way the database
  prints it. Table and column names must be plain identifiers and are not
  quoted in the condition: a column named after a reserved word needs a
  project step.

- **`io.testforge.gherkin.mock.MockSteps`** — over `ScopedMockClient`, in the
  scenario's own scope (`forge.mock.base-url`, `forge.mock.scope-*`):

| Step | |
|---|---|
| `Given the mock answers GET "/stock/book" with 200:` + doc string | JSON bodies get `Content-Type: application/json` |
| `Given the mock answers DELETE "/carts/${cartId}" with 204` | |
| `Then the mock received 1 GET request to "/stock/book"` | counts this scenario's calls only |
| `Then the mock received no POST request to "/stock/book"` | |

  The scope closes after the scenario and its stubs go with it.

## Setup

```groovy
testImplementation project(":module-gherkin")
testImplementation "io.cucumber:cucumber-junit-platform-engine"
testImplementation "org.junit.platform:junit-platform-suite"
```

One suite class runs the feature files on the JUnit Platform; its glue lists
`io.testforge.gherkin.glue`, one package per TestForge module the project
uses, and the project's own steps:

```java
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME,
        value = "io.testforge.gherkin.glue,io.testforge.gherkin.http,com.acme.tests.steps")
class AcceptanceSuite { }
```

Exactly one class in the glue carries `@CucumberContextConfiguration` and
`@SpringBootTest`: the Spring context every scenario runs in. Each module's
steps need that module on the classpath and nothing else — `module-http` is
only `compileOnly` here.

```yaml
forge:
  gherkin:
    scope-per-scenario: true        # default
    scope-prefix: bdd
    correlation-per-scenario: true  # default
    settle: 2s                      # "exactly one" / "no message" watch this long
    scope-messages: false           # true: message steps see only this scenario's messages
```

Scenarios may run in parallel (`cucumber.execution.parallel.enabled=true`):
each has its own scope, so stubs, rows and scoped messages stay apart —
`parallel.feature` in `example-tests` runs four copies at once on the same
paths.

Feature files that need a container carry the Gherkin tag `@containers`;
the JUnit Platform sees it as the tag `containers`, so the default build
skips them and a `containersTest` task runs them. Give their suite
`@Suite(failIfNoTests = false)` — one of the two tasks always filters it empty.

## The project's own steps

"Given a paid order" belongs to the product: write it next to the generic
steps, on `module-state` or `module-flow`, and read the scenario's scope from
`ScenarioContext` when a stub or a record must belong to this scenario only.
`example-tests` shows one (`OrderServiceSteps`) with `orders.feature`.

## Failures

A failure names the request and what differed, not the body — the body is
in the `forge.http` log, redacted:

```
POST /orders answered 404, expected 201 (the body is in the forge.http log)
GET /orders/o-1: the body has no field $.state; top-level fields: [id, status, item]
```
