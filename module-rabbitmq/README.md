# module-rabbitmq

RabbitMQ message probing for tests: wait for the event a service publishes,
prove it was published once or not at all, and start a flow by publishing a
test's own message.

## What's inside

- **`RabbitCollector`** — optional; declares a server-named, exclusive,
  auto-deleted queue of its own, binds it to the exchanges a test watches
  and reads it. RabbitMQ copies a routed message to every bound queue, so
  the services' own queues receive exactly what they did before.
- **`RabbitMessageBuffer`** — bounded in-memory journal per exchange,
  newest-first search.
- **`RabbitProbe` / `RabbitMessageFilter`** — wait by exchange, routing key,
  headers, body fragments or JSON paths; exactly one; none within a window.
- **`RabbitPublisher`** — publish a test's own message with the scenario's
  scope and correlation headers; waits for the broker's confirm.

Buffer, criteria and waiting are `core`'s broker-neutral
`io.testforge.core.messaging`, shared with `module-kafka`: the two probes
behave the same and fail with the same kind of message. Contract checks are
composition, not a dependency — await here, validate with module-contract.

## Configuration

The connection is Spring Boot's own:

```yaml
spring:
  rabbitmq:
    host: rabbit.staging.example.test
    port: 5672
    username: ${RABBIT_USER}
    password: ${RABBIT_PASSWORD}

forge:
  rabbitmq:
    enabled: true                 # the collector; off by default
    bindings:
      - exchange: orders          # topic exchange, every routing key ("#")
      - exchange: payments
        routing-key: payment.*
    max-messages-per-exchange: 1000
    publish:
      scope-header: X-Test-Scope
      correlation-header: X-Request-Id
      confirm-timeout: 10s
```

- **Observes, never consumes.** The collector's queue is its own, gone when
  the connection closes; enabling it on a shared environment changes nothing
  for the services. It shows up as `amq.gen-…` in the management UI.
- **Bound before the first test**: the bindings exist before the Spring
  context finishes starting, so an event the first test triggers is seen.
- **A routing key per exchange type**: `#` is every key on a topic exchange;
  a direct exchange needs the exact key, a fanout ignores it. Headers
  exchanges are not supported, and the default exchange cannot be bound to.
- **A collector that could not start says so**: a binding to an exchange
  that does not exist stops it, and every later timeout starts with
  `The collector stopped after an error and receives nothing: binding … failed: NOT_FOUND …`.

## Usage

```java
@Autowired RabbitPublisher publisher;
@Autowired RabbitProbe probe;

@Test
void payingEmitsOnePaymentEvent() {
    publisher.publish("orders", "order.pay", Map.of("orderId", orderId));

    RabbitMessageFilter paid = RabbitMessageFilter.builder()
            .exchange("payments").routingKey("payment.captured")
            .jsonPathEquals("$.orderId", orderId)
            .build();
    probe.awaitExactlyOne(paid, Duration.ofSeconds(2));
    probe.assertNoMessage(RabbitMessageFilter.builder()
            .exchange("payments").routingKey("payment.refunded")
            .jsonPathEquals("$.orderId", orderId).build(), Duration.ofSeconds(2));
}
```

A publish is synchronous and mandatory: it waits for the broker's confirm,
and a message no queue is bound for fails at that line —
`Publishing to exchange audit with routing key nobody.listens was returned
(312 NO_ROUTE): no queue is bound for it` — instead of a wait timing out
later.

A timeout names the exchange's closest messages by position and failed
criteria, never by body:

```
No RabbitMQ message matching RabbitMessageFilter[exchange=orders, routingKey=order.paid, …].
2 messages buffered on orders; closest:
  - orders/order.placed#1 key=order.placed — failed: routing key
```

## Tests

`RabbitProbeUnitTest`, `TestForgeRabbitAutoConfigurationTest` (offline).
`RabbitMqProbeIT` in `example-tests` runs the collector, publisher and probe
against RabbitMQ 4.1 (tag `containers`, `./gradlew :example-tests:containersTest`).
