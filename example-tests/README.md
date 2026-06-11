# example-tests

The living documentation: one self-contained test per module idea. Runs
offline — an embedded WireMock plays the system under test, in-memory H2
plays the service database.

| Test | Demonstrates |
|---|---|
| `DataHelpersTest` | per-run unique values, `Generators` masks, `%{variable}%` templates |
| `PreparedDataTest` | `@Prepared` fixture injection backed by the prepared-object pool |
| `ScenarioContextCleanupTest` | `ScenarioContextExtension` auto-clear between tests on reused threads |
| `ScopedRequestTemplateTest` | payload template embeds the generated scope id; request lands on the scenario's stub |
| `StateDiffTest` | `StateSnapshot`/`StateDiff` side-effect DB assertions |
| `KafkaCollectorIntegrationTest` | live `KafkaPollingCollector` against embedded broker |
| `ScopedMockIsolationTest` | scoped stubs on a shared mock don't leak between scenarios |
| `DbWaiterTest` | waiting for a row written asynchronously, without sleeps |
| `RepositoryPollingAspectTest` | using an opt-in repository waitBy... method backed by findBy... polling |
| `SchemaValidatorTest` | detecting drift between entity mappings and the real schema |
| `PostgresSchemaValidationIT` | same checks on real Postgres via Testcontainers — `../gradlew :example-tests:containersTest` (needs Docker) |
| `JsonContractValidatorTest` | validating API/queue/file payloads against a neutral message template |
| `FlowRunnerTest` | state paths, role-based branching, `FlowStepDecorator` hooks |
| `KafkaProbeTest` | finding buffered Kafka messages and validating their contracts offline |
| `ResourceUsageMonitorTest` | collecting lightweight JVM resource metrics for run diagnostics |

```bash
../gradlew :example-tests:test
```

When you change a module's behaviour, update its example test in the same
commit — this suite is what keeps the README claims honest.
