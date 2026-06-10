# example-tests

The living documentation: one self-contained test per module idea. Runs
offline — an embedded WireMock plays the system under test, in-memory H2
plays the service database.

| Test | Demonstrates |
|---|---|
| `DataHelpersTest` | per-run unique values and `%{variable}%` template rendering |
| `ScopedMockIsolationTest` | scoped stubs on a shared mock don't leak between scenarios |
| `DbWaiterTest` | waiting for a row written asynchronously, without sleeps |
| `RepositoryPollingAspectTest` | using an opt-in repository waitBy... method backed by findBy... polling |
| `SchemaValidatorTest` | detecting drift between entity mappings and the real schema |
| `JsonContractValidatorTest` | validating API/queue/file payloads against a neutral message template |
| `FlowRunnerTest` | running a deterministic state path with readable failure diagnostics |
| `KafkaProbeTest` | finding buffered Kafka messages and validating their contracts offline |
| `ResourceUsageMonitorTest` | collecting lightweight JVM resource metrics for run diagnostics |

```bash
../gradlew :example-tests:test
```

When you change a module's behaviour, update its example test in the same
commit — this suite is what keeps the README claims honest.
