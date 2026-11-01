# module-http

The API layer every project rewrites: base URLs per environment, timeouts,
correlation, redacted logging — as a preconfigured REST Assured
`RequestSpecification`.

## What's inside

- **`ApiClient`** — `request()` returns a plain REST Assured
  `RequestSpecification` that already knows the environment; `request("name")`
  targets one of several services. Everything after that is REST Assured, not a
  DSL of our own.
- **`ScenarioScopeFilter`** — writes the scenario's mock scope id into the
  outgoing JSON body at the path `module-mock` matches on. This closes the loop
  that otherwise has to be re-tied by hand in every payload.
- **`CorrelationIdFilter`** — one request id per scenario on every call,
  published to `ScenarioContext` under `ScenarioKeys.CORRELATION_ID` so later
  assertions can search service logs and traces by the same value.
- **`HttpLoggingFilter`** — one line per request in the `forge.http` logger
  (the HTTP counterpart of `forge.sql`), headers and bodies at DEBUG.
- **`Redactor`** — masks credentials in headers, bodies and query strings
  (`?api_key=***`: a query name matching a redacted header or field) before
  anything is logged. CI logs outlive the run that produced them.
- **`RetryFilter`** — opt-in retry for infrastructure statuses on safe methods
  only, spaced through `Waiter`.
- **`ApiRequestCustomizer`** — extension point for per-specification concerns
  the module deliberately does not own (authentication, tenant selection,
  request signing).

The module adds no assertion helpers and no response wrapper: REST Assured
already has both, and a second dialect on top would only be one more thing to
learn.

## Configuration

```yaml
forge:
  http:
    base-url: https://api.staging.example.test
    services:                       # optional, for multi-service landscapes
      payments:
        base-url: https://payments.staging.example.test
        headers:
          "[X-Tenant]": demo
    connect-timeout: 5s             # default
    read-timeout: 30s               # default
    headers:                        # sent on every request
      "[X-Client]": testforge
    scope:
      enabled: true                 # default
      json-path: "$.metadata.test_scope"   # default: forge.mock.scope-json-path
      header: X-Test-Scope          # optional, off unless set
    correlation:
      enabled: true                 # default
      header: X-Request-Id          # default
    logging:
      enabled: true                 # default
      bodies: true                  # default; DEBUG level on logger forge.http
      max-body-chars: 2000          # default
      redact-headers: [authorization, cookie, set-cookie, x-api-key]
      redact-json-fields: [password, token, secret, access_token]
    retry:
      enabled: false                # default
      timeout: 10s
      delay: 500ms
      statuses: [502, 503, 504]
      methods: [GET, HEAD, OPTIONS]
```

Header maps need the bracket syntax shown above — Spring's relaxed binding
would otherwise rewrite `X-Tenant` into a canonical lower-case form.

`scope.json-path` deliberately has no separate default: when unset it reads
`forge.mock.scope-json-path`. The two values describe the same field, and a
project that has to keep them in sync by hand eventually will not.

## Usage

As a published library:

```groovy
dependencies {
    testImplementation "io.testforge:module-http:1.3.0-SNAPSHOT"
}
```

`core`, Spring Boot auto-configuration, and REST Assured are transitive
dependencies. The consuming test project still owns its Spring Boot test
version and environment configuration.

```java
@Autowired
ApiClient api;

@Test
void createsPayment() {
    api.request()
            .contentType(ContentType.JSON)
            .body(Map.of("amount", 100))
            .post("/payments")
            .then()
            .statusCode(201);
}
```

With a scenario-scoped mock, the payload no longer has to carry the scope id
itself:

```java
try (MockScope scope = mocks.scope()) {
    scope.stub(post(urlPathEqualTo("/payments")).willReturn(okJson("{\"result\":\"scoped\"}")));

    api.request()
            .contentType(ContentType.JSON)
            .body("{\"amount\":100}")   // the filter embeds the scope id
            .post("/payments");
}
```

The filter is a no-op when the scenario never opened a scope: tests that talk
only to the real system under test keep sending untouched payloads.

## Waiting for an asynchronous API

Start a job, then wait until it is done — no sleep, no loop in the test:

```java
@Autowired ResponsePoller poll;

Response done = poll.until(() -> api.request().get("/jobs/{id}", id),
        allOf(status(200), jsonPath("status", "DONE")));        // ResponseConditions.*
```

It polls through `Waiter` with `forge.wait.timeout` / `poll-interval`
(overridable per call: `poll.until(call, condition, Duration.ofMinutes(2))`).
A call that throws counts as "not yet". On timeout the test fails with the
condition, the number of attempts and the last response — status and a
redacted, shortened body:

```
Response did not match status 200 and $.status == DONE within PT30S (61 attempts);
last response: status 202, body {"status":"PENDING"}
```

## Every page of a list

```java
List<Order> all = Pagination.byPageNumber(page -> api.request().queryParam("page", page).get("/orders"))
        .items("items", Order.class)      // GPath; "$" (default) for a root array
        .collect();
```

| Strategy | Call receives | Stops at |
|---|---|---|
| `byPageNumber(page -> …)` | page number, from 1 (`startAt(0)` for zero-based) | an empty page |
| `byOffset(offset -> …, limit)` | offset | a page shorter than `limit` |
| `byCursor(cursor -> …, "next_cursor")` | `null`, then the cursor from the response | a missing or blank cursor |
| `byLinkHeader(url -> …)` | `null`, then the `rel="next"` URL | no `rel="next"` link |

It fails with an `AssertionError` instead of misbehaving: on a non-2xx page
(`Page 2 answered 500`), when the server returns the same page twice (it
ignores the paging parameter — the classic endless loop), and after
`maxPages` pages (default 100).

## When a test fails: its HTTP exchanges in the run index

The module keeps the scenario's last exchanges in memory (per thread,
redacted like the log). Register the extension and a failed test publishes
them to the run index as `module-http/<Class>-<method>.md` — what was sent,
what came back, oldest first — so a red CI run explains itself:

```java
@SpringBootTest
@ExtendWith(HttpDiagnosticsExtension.class)
class OrdersTest { ... }
```

```yaml
forge:
  http:
    diagnostics:
      enabled: true   # default
      last: 10        # exchanges kept per scenario
```

The original failure is always rethrown unchanged. Nothing is written for
passing tests, and nothing at all without `module-reporting` enabled. With
`module-auth`, each exchange also says which role it ran as (`As: admin
(header Authorization)`).

To check every response against the OpenAPI document during ordinary tests,
turn on the contract guard (`forge.contract-guard.enabled`, in
[module-api-explorer](../module-api-explorer/README.md#contract-guard-the-contract-checked-in-every-functional-test)).

For role-based tokens, add [module-auth](../module-auth/README.md), configure
`forge.auth.roles`, and select a role with `@AsUser("admin")`. Its `AuthFilter`
is applied automatically by `ApiClient`; no custom token code is needed.

For project-specific authentication outside those token sources, use an
`ApiRequestCustomizer`:

```java
@Bean
ApiRequestCustomizer bearerToken(TokenProvider tokens) {
    return (request, service) -> request.header("Authorization", "Bearer " + tokens.forRole("admin"));
}
```

## Adapting to a project

1. Set `base-url` per environment profile; add `services.<id>` when tests span
   several backends.
2. Leave `scope.json-path` unset unless the mock module is absent — it follows
   `forge.mock.scope-json-path`.
3. Extend `logging.redact-*` with the field names your product actually uses
   for credentials before the first CI run uploads a log.
4. Add `module-auth` for role-based tokens. For project-specific authentication,
   use an `ApiRequestCustomizer` (per specification) or a REST Assured `Filter`
   bean (per request). Any `Filter` bean in the context is applied to every request.
5. Turn `retry.enabled` on only for environments where infrastructure noise is
   real, and keep `retry.methods` to methods that are safe to repeat.

## Limits

- Scope injection rewrites JSON **object** bodies sent as a string or byte
  array. Object/POJO bodies are serialized by REST Assured after the filters
  run, so those requests need `scope.header` instead. Array indices in the
  scope path are rejected. A header-scoped request only lands on a scoped stub
  when the mock matches that header too: set `forge.mock.scope-header`, which
  `scope.header` follows when left unset.
- Bodies are re-serialized by Jackson when the scope is injected: formatting
  and duplicate keys are not preserved.
- Redaction covers JSON documents and `field=value` text. It is not a general
  sanitizer — do not rely on it for payloads whose secrets live in free text.
- `RetryFilter` runs innermost; a filter registered after it is applied on the
  first attempt only.

## Agent notes

- Compose, don't wrap: `ApiClient.request()` hands back a real
  `RequestSpecification`. Never add assertion or response helpers here.
- A specification from `RequestSpecBuilder` cannot be sent on its own — it
  carries no response specification. `ApiClient` merges it into
  `RestAssured.given()`.
- `ScenarioScopeFilter` must stay a no-op without an open scope. Injecting a
  field into every request would reshape traffic to the real system.
- Retry is off by default and never applies to unsafe methods; retried
  attempts always log at WARN so a suite leaning on retries is visible.
- Waiting between attempts goes through `Waiter` — no sleeps in this module
  either.

## Traffic summary

`HttpTrafficSummary` (on by default, `forge.http.summary.enabled`) counts,
per operation, the requests the suite sent, their status classes, transport
failures and p50 / p95 / max latency. An operation is the method and the
path shape — `/orders/42` reads `/orders/{n}`, UUIDs `{uuid}`, long ids
`{id}` — without host or query. With module-reporting it writes
`http-summary.json` to the run index when the context stops; module-quality
turns it into the reliability dimension, where a 5xx a test expected or
swallowed still counts. No body, header or query value is kept; at most 500
operations and 2000 latency samples each.
