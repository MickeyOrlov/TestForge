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
the probes do; no step prints a message body. Steps for the database and
mocks follow ([plan](../docs/gherkin-layer-roadmap.md)).

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
```

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
GET /orders/o-1: the response has no field $.state; top-level fields: [id, status, item]
```
