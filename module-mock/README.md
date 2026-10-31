# module-mock

Scenario-scoped stubbing on a **shared** WireMock server — the pattern that
makes parallel test execution safe without giving every test its own mock
instance.

## The idea

Isolation comes from **request matching**, priority only settles ties:

- Default stubs (registered by the environment, priority 10+) answer all
  traffic — the happy path.
- A test that needs a special outcome registers a stub through `MockScope`.
  The scope transparently adds a body matcher on the scenario id
  (`forge.mock.scope-json-path`) and priority 1. The stub fires **only** for
  requests carrying that scenario's id.
- `MockScope` is `AutoCloseable`: closing it deletes its stubs, so the shared
  server never accumulates garbage.

## Configuration

```yaml
forge:
  mock:
    base-url: http://wiremock.staging.example.test:8080
    scope-json-path: "$.metadata.test_scope"  # default: $.testScope
```

The bean is only created when `base-url` is set.

### Requests without a body

A body JSON path cannot scope a GET or DELETE. Point the marker at a header,
query parameter or cookie the system under test forwards instead — exactly one
of them; it replaces the JSON path:

```yaml
forge:
  mock:
    base-url: http://wiremock.staging.example.test:8080
    scope-header: X-Test-Scope        # or scope-query-param / scope-cookie
```

`module-http` writes `forge.http.scope.header` from `forge.mock.scope-header`
when it is not set itself, so the header is named in one place. Query
parameters and cookies have to be set by the code that builds the request.

## Usage

```java
try (MockScope scope = mocks.scope(scopeId)) {
    scope.stub(post(urlPathEqualTo("/downstream/status"))
            .willReturn(okJson("{\"result\":\"scenario-specific\"}")));

    // drive the scenario; only requests carrying this scope id hit the stub
}
```

When the scenario itself owns the id, let the client generate and publish it:

```java
try (MockScope scope = mocks.scope()) {   // generated id
    // payload builders read it from the scenario context:
    String scopeId = ScenarioContext.get(ScopedMockClient.TEST_SCOPE);
    ...
}
```

Pair with `ScenarioContextExtension` so the published id never leaks into the
next test on a reused worker thread.

### Asserting on the scenario's own traffic

The WireMock journal is server-wide; a scope filters it to its own requests
with the same marker its stubs use:

```java
try (MockScope scope = mocks.scope()) {
    // drive the scenario ...
    scope.verify(1, postRequestedFor(urlPathEqualTo("/downstream/status")));
    List<LoggedRequest> mine = scope.requests();     // matched or not
}
```

Another scenario's traffic never counts, so these assertions stay correct when
tests run in parallel against one shared mock.

### A downstream that fails, then recovers

To test the system's retries, answer the same request differently each time:

```java
try (MockScope scope = mocks.scope()) {
    scope.sequence(post(urlPathEqualTo("/payments")),
            aResponse().withStatus(503),
            aResponse().withStatus(503),
            okJson("{\"status\":\"accepted\"}"));   // repeats from here on
    // drive the scenario; assert it retried: scope.verify(3, postRequestedFor(...))
}
```

The sequence is a WireMock scenario named after the scope id, so parallel
scenarios each walk their own and the default stubs answer everyone else.
Faults work as steps too: `aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)`
or `.withFixedDelay(5000)` for a timeout.

## Diagnostics

When a `MockScope` closes, it publishes a JSON diagnostic artifact (`<scopeId>.json`) summarizing registered stubs and scope-attributed unmatched requests. Each unmatched request includes a `closestStub` field explaining why it matched no scoped stub:

```json
{
  "scopeId": "scenario-123",
  "scopeJsonPath": "$.testScope",
  "stubs": [ ... ],
  "stubCount": 1,
  "unmatchedRequests": [
    {
      "method": "POST",
      "url": "/no/such/endpoint",
      "loggedDate": "2026-08-17T20:00:00Z",
      "closestStub": {
        "stubIndex": 0,
        "id": "a1b2c3d4-...",
        "name": "my-stub",
        "distance": 0.25,
        "mismatches": [
          {
            "component": "url",
            "matcher": "urlPathEqualTo",
            "expected": "/orders",
            "actual": "/no/such/endpoint"
          }
        ]
      }
    }
  ],
  "unmatchedCount": 1
}
```

If no scoped stubs were registered for the scope, `closestStub` is set to `null` with a `reason`:

```json
{
  "closestStub": null,
  "reason": "no scoped stubs registered"
}
```

If no stub could be ranked (for instance, if matcher execution threw an exception for every candidate stub), the analyzer falls back to the first stub without measuring a distance, and the `distance` field is omitted from `closestStub`.

### Mismatch components and redaction guarantee

Mismatch elements in `mismatches` report differences by component:
- **`method`** and **`url`**: carry `expected` and `actual` values (both of which the artifact already published).
- **`body`**: carries structural metadata only (the matcher type name, e.g. `matchesJsonPath`, the `jsonPath` expression ONLY when the pattern is the scope marker (`scopeMarker: true`), and a `scopeMarker` boolean).
- **`header`**, **`queryParam`**, and **`cookie`**: carry the key `name` only.
- **`unexplained`**: appended when all evaluated components match but the stub overall mismatched, carrying `unevaluatedComponents` listing by name only any components set on the stub that the analyzer does not evaluate (e.g. `basicAuth`, `multipart`, `customMatcher`, `host`, `clientIp`, `port`, `pathParams`, `formParams`).

**Redaction Guarantee:** No request body content, no header, query parameter, or cookie value, no payload length, and no hash is ever written — a hash of a low-entropy body is still a leak.

### Distance ranking caveat

The `distance` field represents WireMock's weighted-average distance across request components. Because `MockScope` injects a scope marker matcher into every scoped stub, that marker participates in the body sub-aggregate distance. Distance-based stub ranking is a diagnostic **hint** to assist debugging, not proof of developer intent.

## Adapting to a project

Find the field that uniquely ties a downstream request to one scenario — an
explicit test scope, correlation id or request id that the system under test
echoes into mock-bound calls — and point `scope-json-path` at it. If no such
field exists for some flow, those few tests must run serially; tag them
explicitly rather than serializing the whole suite.

## Agent notes

- Isolation comes from the request-body matcher, priority only settles ties:
  defaults are priority 10+, scoped stubs priority 1 WITH the scope matcher.
- `forge.mock.scope-json-path` is THE adaptation point — find the field the
  system under test echoes into downstream calls before anything else.
- `scope()` (no args) publishes the generated id to `ScenarioContext` under
  `ScopedMockClient.TEST_SCOPE`; pair with `ScenarioContextExtension`.
