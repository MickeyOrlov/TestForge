# module-kafka: working on this module

Read the root [AGENTS.md](../AGENTS.md) first. This file is about *changing*
`module-kafka`; how to *use* it lives in [README.md](README.md).

**Scope:** `KafkaMessageBuffer`, `KafkaProbe` (await, exactly one, none within a window), `KafkaMessageFilter`, `KafkaPublisher`, `KafkaDiagnosticsExtension`, optional `KafkaPollingCollector`. Plan: [docs/kafka-layer-roadmap.md](../docs/kafka-layer-roadmap.md).

**May depend on:** `core` only. Buffer, criteria and waiting are `core`'s broker-neutral `io.testforge.core.messaging`; this module adds what is Kafka (records, collector, publisher, diagnostics) and keeps its public names. Plan: [docs/messaging-layer-roadmap.md](../docs/messaging-layer-roadmap.md). Never add a dependency on `module-contract`; they compose in the test.

## Invariants (do not break)

- Bounded buffer, newest-first search.
- Collector runs only with `forge.kafka.enabled=true`; it joins a unique group per run by default, is assigned before `start()` returns, and records why it stopped in the buffer.
- Waiting goes through `Waiter`; absence and settle windows use Awaitility's `during` — never a sleep.
- Failure messages name offsets, keys and failed criteria, never a payload; the diagnostics report includes payloads only with `forge.kafka.diagnostics.payloads=true`.

## Tests

Own: `KafkaMessageBufferTest`, `KafkaProbeUnitTest`. Examples: `KafkaProbeTest`, `KafkaCollectorIntegrationTest` (embedded broker).

## Before you finish

- `./gradlew :module-kafka:test :architecture-tests:test` is green, then `./gradlew build`.
- Public behaviour changed → update [README.md](README.md) and the example test
  in the same commit (`example-tests` is the living documentation).
- New dependency on another module → it must be listed in
  `architecture-tests/.../Modules.java` `ALLOWED_DEPENDENCIES`, and it must be
  a real need, not a convenience.
