# module-faults

An opt-in handle over [Toxiproxy](https://github.com/Shopify/toxiproxy) using
[Testcontainers](https://java.testcontainers.org/modules/toxiproxy/) 2.0.5 and its Java client 2.1.11.
The pinned server image is ghcr.io/shopify/toxiproxy:2.13.0. No proxy or chaos scheduler is implemented here.

## Configuration

```yaml
forge:
  faults:
    enabled: true
    proxies:
      orders-db: db:5432
    allowed-upstreams: [db:5432]
    docker-network: my-test-network # upstreams resolve inside this Docker network
    slots: 16                      # max 32 managed ports; fail rather than sharing a scope
```

Enabling creates beans only; Docker starts on the first explicit `on` call.
A target must match the exact configured host:port allowlist. There are no wildcards,
CIDRs, implicit targets or production defaults. DNS names are trusted test-network identities,
not protection against DNS rebinding. Never point this tool at production.

## Usage and isolation

```java
try (FaultScope network = faults.on("orders-db")) {
    String jdbc = "jdbc:postgresql://" + network.endpoint().address() + "/orders";
    // Configure/create the scenario's datasource or service using this endpoint first.
    network.latency(Duration.ofSeconds(2)).jitter(Duration.ofMillis(100));
    // Assert the service times out cleanly rather than hanging.
}
```

Each scope creates a fresh UUID-named proxy and gets a different endpoint. A test must
route its own service/client through that endpoint; changing a global Spring datasource
for parallel tests would defeat isolation. Create per-scenario service/client instances,
or give a class one exclusive proxy and serialize that class's tests. Direct connections
bypassing the endpoint are unaffected. Close pools/clients before closing their scope.

Methods: latency(Duration), jitter(Duration) after latency, timeout(Duration), resetPeer(),
bandwidth(long KB/s), down(). Faults use Toxiproxy's native DOWNSTREAM direction (server
to client); down disables the whole proxy. timeout(0) drops data until cleanup. Reset is
applied to active and subsequent links; tests must remove it before recovery connections.
restore() removes this scope's toxics and re-enables its own proxy without changing the endpoint.
Closing deletes the entire owned proxy and its toxics, including after test failure.

A context/JVM shutdown hook closes outstanding scopes and the managed container. Cleanup
errors fail visibly and can be retried with scope.close(); try-with-resources suppresses
cleanup errors onto an existing test failure. A failed create/delete quarantines its port.
The controller's final cleanup also searches for its owned IDs after uncertain creates.

## Existing controller

Set server-url: http://toxiproxy.test:8474, data-host if the proxy data listener is
reachable by a different hostname, first-port and slots for an exclusively reserved
port pool, and external-cleanup-acknowledged: true. Control origin must be explicit HTTP(S)
without credentials/query/path; use a trusted test network or secured tunnel. The official
Java client supplies control HTTP; its connection timeout behavior is upstream's.
No global reset, foreign proxy mutation or attachment to existing proxies occurs.
Data ports must be reachable on the same port numbers as the controller listeners (identity port mapping or direct host deployment). Separate JVMs must reserve non-overlapping port pools. Within one controller scopes are isolated.

Toxiproxy has no toxic TTL: no client library can guarantee remote cleanup after kill -9,
loss of the controller, or loss of connectivity. External mode requires an operator-owned
cleanup/restart policy for this dedicated test controller; the acknowledgement accepts that
operational responsibility. Managed mode uses Testcontainers/Ryuk cleanup, subject to Docker
availability. This is not production chaos or an OS-level cleanup guarantee.

## Evidence and verification

Each closed scope writes a run-index faults.json (collision-safe faults-2.json etc.) through
ArtifactSink, with logical proxy name, UUID, application times, units/durations and cleanup
status. No payload, upstream host, credentials or raw controller exception is published.
Without reporting the sink is a no-op. Quality integration is deliberately absent.

```bash
./gradlew :module-faults:test :architecture-tests:test
./gradlew :example-tests:faultsTest
./gradlew build
```

The native examples exercise PostgreSQL timeouts under two-second latency, restoration,
parallel-scope isolation and Kafka recovery after a connection reset against owned containers.
