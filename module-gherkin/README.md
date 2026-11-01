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

Steps for Kafka, RabbitMQ, the database and mocks follow
([plan](../docs/gherkin-layer-roadmap.md)).

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
```

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
