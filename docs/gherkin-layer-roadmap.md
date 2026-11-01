# Gherkin layer roadmap

Some organizations write acceptance tests as Gherkin feature files and want
the same guarantees TestForge gives JUnit tests: a scope per scenario so
parallel runs do not fight over shared stubs, correlation ids, waits instead
of sleeps, failure messages that say how close the system came. `module-gherkin`
is the bridge — ready-made steps over the existing modules — and nothing
more. It is optional: TestForge stays JUnit-first, and a project that does
not use Gherkin deletes the module.

| Step | What | Where | Status |
|---|---|---|---|
| 1 | **Scenario lifecycle and HTTP** — a scope and correlation id per scenario, `${name}` variables, request / status / JSON-path / remember steps over `ApiClient` | `module-gherkin` (`io.testforge.gherkin.glue`, `io.testforge.gherkin.http`) | shipped |
| 2 | **Messages** — publish, await, exactly one, none within a window, for Kafka and RabbitMQ | `io.testforge.gherkin.kafka`, `io.testforge.gherkin.rabbitmq` | shipped |
| 3 | **Database and mocks** — a row exists / changed, a scoped stub answers, a downstream call was made | `io.testforge.gherkin.db`, `io.testforge.gherkin.mock` | planned |
| 4 | **Review** | `module-gherkin` | planned |

## Shape

- **Glue per adapter.** Steps for each module live in their own package, and
  that module is a `compileOnly` dependency: a project lists the packages it
  uses in `cucumber.glue` and needs only those modules on its classpath.
  `io.testforge.gherkin.glue` (lifecycle hooks) is always listed.
- **Steps are thin.** Each step calls the module's own API; behaviour and
  failure messages stay in the module, not in a second copy written for
  Gherkin. A step that needs logic the module lacks is a change to the module.
- **The project writes its domain steps.** "Given a paid order" belongs to the
  product, built on `module-state` or `module-flow`; the module ships the
  generic vocabulary only.
- **No Cucumber in other modules.** Nothing outside `module-gherkin` depends
  on Cucumber.
