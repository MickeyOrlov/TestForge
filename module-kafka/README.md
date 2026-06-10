# module-kafka

Kafka message probing for tests and scheduled contract drift checks.

## What's inside

- **`KafkaPollingCollector`** — optional background collector that polls
  configured topics through Spring Kafka `ConsumerFactory`.
- **`KafkaMessageBuffer`** — bounded in-memory message journal, newest-first
  search, safe for asynchronous tests.
- **`KafkaProbe` / `KafkaMessageFilter`** — wait for messages by topic, key,
  headers, text fragments, or JSON paths.

Contract checks are **composition, not a dependency**: the probe finds the
message, module-contract's validator judges its shape. Either module can be
deleted without breaking the other.

The collector is disabled by default. Enable it only in an environment profile
that has Kafka access; example tests use the buffer directly and stay offline.

## Configuration

```yaml
spring:
  kafka:
    bootstrap-servers: kafka.staging.example.test:9092
    consumer:
      group-id: testforge-probe
      auto-offset-reset: latest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer

forge:
  kafka:
    enabled: true
    topics:
      - partner.events
    poll-timeout: 500ms
    max-messages-per-topic: 1000
    seek-to-beginning-on-start: false
```

## Usage

```java
KafkaMessageFilter filter = KafkaMessageFilter.builder()
        .topic("partner.events")
        .key("request-123")
        .jsonPathEquals("$.payload.status", "accepted")
        .build();

KafkaMessage message = kafkaProbe.awaitMessage(filter);

// optional shape check — composes with module-contract when it is present
contractValidator.assertValid(message.value(), contract);
```
