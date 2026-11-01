# module-rabbitmq: working on this module

Read the root [AGENTS.md](../AGENTS.md) first. This file is about *changing*
`module-rabbitmq`; how to *use* it lives in [README.md](README.md).

**Scope:** `RabbitMessageBuffer`, `RabbitProbe`, `RabbitMessageFilter`, `RabbitPublisher`, optional `RabbitCollector`. Plan: [docs/messaging-layer-roadmap.md](../docs/messaging-layer-roadmap.md).

**May depend on:** `core` only. Buffer, criteria and waiting are `core`'s `io.testforge.core.messaging`, shared with `module-kafka` — a behaviour both brokers need goes there, not into one adapter. Never add a dependency on `module-contract` or `module-kafka`.

## Invariants (do not break)

- The collector only observes: a server-named, exclusive, auto-deleted queue
  of its own, never a consumer on a service's queue, never an ack that could
  take a message from someone else.
- Collector runs only with `forge.rabbitmq.enabled=true`; its bindings exist
  before `start()` returns, and a failure is recorded in the buffer.
- Nothing connects at context start unless the collector is enabled: the
  publisher opens a channel per publish.
- Publishing is synchronous, confirmed and mandatory — an unroutable message
  fails where it was published.
- Failure messages name exchange, routing key, delivery number and failed
  criteria, never a body.

## Tests

Own: `RabbitProbeUnitTest`, `TestForgeRabbitAutoConfigurationTest`. Example: `RabbitMqProbeIT` (tag `containers`, real broker).

## Before you finish

- `./gradlew :module-rabbitmq:test :architecture-tests:test` is green, then `./gradlew build`.
- Collector or publisher changed → `./gradlew :example-tests:containersTest --tests '*RabbitMqProbeIT'`
  (needs Docker): neither has an offline broker to run against.
- Public behaviour changed → update [README.md](README.md) and the example test
  in the same commit.
