# Messaging layer roadmap

`module-kafka` waits for a message by topic, key, header or JSON path,
proves what was not sent or sent twice, publishes a test's own events and
explains a failed test with the traffic around it. None of that is about
Kafka: a team on RabbitMQ needs exactly the same assertions. The ROADMAP
held the extraction back until a second broker was actually added; this plan
adds RabbitMQ and extracts what the two share, in that order of need.

| Step | What | Where | Status |
|---|---|---|---|
| 1 | **Broker-neutral core** — message, bounded buffer, criteria, probe (await, exactly one, none within a window, closest messages on a timeout) | `core` (`io.testforge.core.messaging`), `module-kafka` rebuilt on it | shipped |
| 2 | **RabbitMQ adapter** — a collector on a queue of its own per run, bound to the exchanges a test watches, so it never takes a message from the system's consumers; probe and filter by exchange, routing key, header, JSON path; publisher with the scenario's scope and correlation headers | `module-rabbitmq` | shipped |
| 3 | **Messages in failure diagnostics** — the traffic of a failed test in the run index, for both brokers through one renderer | `core`, `module-kafka`, `module-rabbitmq` | planned |
| 4 | **Review** — close the gaps a review of the layer finds, including a real broker run in `containersTest` | all three | planned |

## Why `core`, not a `module-messaging`

The shared part is four small types with no broker dependency: a message
interface, a buffer, criteria and a probe. A module of its own would need an
auto-configuration and properties with nothing to configure. `core` already
holds what several modules share (`Waiter`, `ArtifactSink`, `JsonPath`) and
the rule is "shared classes only when at least two modules need them" — two
do now.

## Invariants carried over

- Message collection and contract validation stay separate: neither broker
  module depends on `module-contract`; they compose in the test.
- Bounded buffer, newest-first search, waiting through `Waiter` and
  Awaitility's `during` — never a sleep.
- Failure messages name positions, keys and failed criteria, never a payload.
- The collector only observes: Kafka joins a consumer group of its own per
  run; RabbitMQ binds an exclusive, auto-deleted queue of its own.
- The public Kafka API (`KafkaProbe`, `KafkaMessageFilter`,
  `KafkaMessageBuffer`, `KafkaMessage`) keeps its names, signatures and
  messages; the extraction is invisible to its users.
