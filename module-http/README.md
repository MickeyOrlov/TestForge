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
- **`Redactor`** — masks credentials in headers and bodies before anything is
  logged. CI logs outlive the run that produced them.
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

Authentication belongs to the adapted project until a dedicated module exists:

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
4. Add authentication as an `ApiRequestCustomizer` (per specification) or a
   REST Assured `Filter` bean (per request). Any `Filter` bean in the context
   is applied to every request.
5. Turn `retry.enabled` on only for environments where infrastructure noise is
   real, and keep `retry.methods` to methods that are safe to repeat.

## Limits

- Scope injection rewrites JSON **object** bodies sent as a string or byte
  array. Object/POJO bodies are serialized by REST Assured after the filters
  run, so those requests need `scope.header` instead. Array indices in the
  scope path are rejected.
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
