# module-kafka

Kafka message probing for tests and scheduled contract drift checks.

## What's inside

- **`KafkaPollingCollector`** — optional background collector that polls
  configured topics through Spring Kafka `ConsumerFactory`.
- **`KafkaMessageBuffer`** — bounded in-memory message journal, newest-first
  search, safe for asynchronous tests.
- **`KafkaProbe` / `KafkaMessageFilter`** — wait for messages by topic, key,
  headers, text fragments, or JSON paths; exactly one; none within a window.
- **`KafkaPublisher`** — publish a test's own event with the scenario's
  scope and correlation headers.

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

### Publishing a test event

Start the flow under test the way production starts it — through the topic:

```java
@Autowired KafkaPublisher kafkaPublisher;

KafkaMessage sent = kafkaPublisher.publish("orders.commands", orderId,
        Map.of("type", "PAY", "orderId", orderId));   // a String goes as is, anything else as JSON
```

```yaml
forge:
  kafka:
    publish:
      scope-header: X-Test-Scope         # the scenario's scope id (ScenarioContext), like module-http
      correlation-header: X-Request-Id   # the scenario's correlation id
      headers: { source: testforge }     # on every published event
      send-timeout: 10s                  # default
```

The bean exists wherever Spring Boot made a `KafkaTemplate`; it sends
nothing until a test publishes. The send waits for the broker's
acknowledgement, so a rejected event fails at the publishing line. Headers
passed to `publish(..., headers)` win over configured ones.

### What must not happen, and duplicates

```java
// the matching event, and no second one within 2s: a retrying producer's duplicate fails
kafkaProbe.awaitExactlyOne(accepted, Duration.ofSeconds(2));

// an event that must never be sent: fails if one is buffered or arrives within 3s
kafkaProbe.assertNoMessage(refundFor(orderId), Duration.ofSeconds(3));
```

`findMessages(filter)` returns every buffered match, newest first.

### When nothing matched

A timeout names how close the topic's messages came, without printing any
payload:

```
No Kafka message matching KafkaMessageFilter[topic=orders, key=o-1, ...].
3 messages buffered on orders; closest:
  - orders/0@0 key=o-1 — failed: $.status
  - orders/0@1 key=o-2 — failed: key, header X-Scope
```

An empty topic says so and points at `forge.kafka.topics`: the most common
reason is a topic the collector was never told to read.

### When a test fails: its Kafka traffic

```java
@SpringBootTest
@ExtendWith(KafkaDiagnosticsExtension.class)
class OrderEventsTest { ... }
```

A failed test publishes `module-kafka/<Class>-<method>.md` to the run index:
the events it published through `KafkaPublisher`, then the messages that
reached the buffer while it ran. With `forge.kafka.publish.scope-header` set
and a scope in the scenario, only messages carrying that scope are listed —
parallel tests' traffic stays out.

```yaml
forge:
  kafka:
    diagnostics:
      last: 20          # per list
      payloads: false   # default: topic/partition@offset, key, timestamp, header names
```

Payloads appear only with `payloads: true`, shortened to 500 characters:
this module has no redactor, and event bodies carry personal data as often
as HTTP ones. The original failure is rethrown unchanged.

## Agent notes

- Contract checks compose in the test (`awaitMessage` + validator) — never
  add a module-contract dependency here.
- The collector is enabled per environment profile only; example tests feed
  the buffer directly and stay offline.
- Search messages by scenario-unique ids/keys; never assert on buffer counts
  under parallel execution.
