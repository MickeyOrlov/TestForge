# module-gherkin: working on this module

Read the root [AGENTS.md](../AGENTS.md) first. This file is about *changing*
`module-gherkin`; how to *use* it lives in [README.md](README.md).

**Scope:** Cucumber glue over the TestForge modules — scenario lifecycle (`glue`), `ScenarioVariables`, and one step package per module (`http`, `kafka`, `rabbitmq`, `db`, `mock`). Plan: [docs/gherkin-layer-roadmap.md](../docs/gherkin-layer-roadmap.md).

**May depend on:** `core`; every other module only `compileOnly`, each listed in `Modules.ALLOWED_DEPENDENCIES`. Nothing outside this module depends on Cucumber.

## Invariants (do not break)

- One step package per module (`io.testforge.gherkin.<module>`), touching
  only that module: a project that lists the package in `cucumber.glue`
  needs that module and no other.
- Steps are thin: they call the module's API. Behaviour or a failure message
  the module lacks is a change to the module, not logic written here.
- Every scenario opens with a cleared `ScenarioContext`, its own scope and
  correlation id (`ScenarioHooks`), and the context is cleared after it.
- Step classes use constructor injection; cucumber-spring creates them per
  scenario, so their fields are scenario state.
- Failure messages name the request and what differed, never a body.
- Every step works with scenarios in parallel: no static mutable state, all
  scenario state in the step instance or `ScenarioContext`. `parallel.feature`
  in example-tests runs with parallelism 4 and must stay green.
- Message steps match by the criteria given, plus the scope header only with
  `forge.gherkin.scope-messages`; never assume the system propagates it.
- Generic vocabulary only; domain steps belong to the adapted project.

## Tests

Own: `ScenarioVariablesTest`, `MessageStepsTest`, `DbAndMockStepsTest`, `ScenarioDiagnosticsTest` (a failing scenario through the Cucumber engine). Examples: `GherkinExampleSuite` (`features/orders.feature`, `payments.feature` on an embedded Kafka broker, `stock.feature` with mock and database steps) and `GherkinRabbitSuite` (`features-containers/payments-rabbitmq.feature`, tag `containers`).

## Before you finish

- `./gradlew :module-gherkin:test :example-tests:test --tests '*GherkinExampleSuite' :architecture-tests:test` is green, then `./gradlew build`.
- A new step → a scenario in a feature file under `example-tests`, and a row
  in the README's step table.
