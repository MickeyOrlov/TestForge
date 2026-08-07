# module-api-explorer

Give it an OpenAPI document and an environment; get back what the API actually
does — which operations answer, which disagree with their own document, and
which nobody could call without more information.

`module-api-discovery` reads the document. This module runs it.

## What's inside

- **`ApiExplorerRunner`** — explores every configured spec and writes the
  report. `assertHealthy()` fails a JUnit job; `run()` just returns the result.
- **`SafetyPolicy`** — decides what may be sent at all. `GET`, `HEAD` and
  `OPTIONS` need no opt-in; everything else needs two.
- **`RequestPlanner` / `RequestValueResolver` / `SchemaValueFactory`** — turn an
  operation into a request, or into a reason it cannot become one. Values come
  from configuration, then the document's examples, defaults and enums, then a
  deterministic value derived from the declared type.
- **`ExchangeExecutor`** — the one HTTP seam.
  `ApiClientExchangeExecutor` sends through `module-http`, so authentication,
  correlation ids, timeouts and retry are whatever the project already
  configured.
- **`ResponseContractChecker`** — compares the real response with the declared
  one: undocumented status, unexpected content type, missing required fields,
  undocumented fields, incompatible types, malformed JSON.
- **`ApiObservation`** — what one operation did, once. The unit everything else
  is built from.

## Configuration

Specs are **not** configured here. The explorer reads the registry
`module-api-discovery` already owns, exactly as `module-api-codegen` does — one
document, one place to point at it.

```yaml
forge:
  api-discovery:
    specs:
      demo:
        location: classpath:/openapi/demo-api.yaml
  http:
    base-url: https://api.staging.example.test
  api-explorer:
    enabled: false                # default; nothing exists until this is true
    output-dir: build/api-explorer
    service:                      # optional forge.http.services.<id>
    specs: []                     # empty = every configured spec
    methods: [GET, HEAD, OPTIONS] # default
    allow-unsafe-methods: false   # default; required for anything else
    include-paths: ["/**"]
    exclude-paths: []
    max-operations: 200
    max-body-chars: 4000
    parameters:
      defaults:
        taskId: "task-1"
      operations:
        listReports:
          from: "2024-03-01"
    fail-on:
      contract-mismatch: false     # default: mismatches are information
      failure: true                # default: an unreachable endpoint is not
```

### Why two keys for write methods

Listing `POST` under `methods` is not enough; `allow-unsafe-methods` must also
be on. One key is easy to inherit by copying another project's configuration.
Two is a decision. An exploration run with `DELETE` enabled against a shared
environment is not a test, it is an incident.

`exclude-paths` is a convenience for narrowing a large document, not a safety
mechanism: no glob distinguishes `POST /orders` from
`POST /orders/{id}/cancel`. The method gate is what protects the environment.

## Usage

```java
@Autowired
ApiExplorerRunner explorer;

@Test
void theStagingApiMatchesItsDocument() {
    explorer.assertHealthy();
}
```

Authentication is `module-http`'s job and needs nothing here:

```java
@Bean
ApiRequestCustomizer bearerToken(TokenProvider tokens) {
    return (request, service) -> request.header("Authorization", "Bearer " + tokens.forRole("readonly"));
}
```

## Artifacts

```
build/api-explorer/
  report.json                       # summaries: outcome, status, duration, artifact path
  report.md                         # the review artifact
  <spec>/observations/<operation>.json   # one full observation per operation
```

Names are deterministic: the operation id, lower-cased and file-safe, with a
stable numbered suffix on collision. Two runs against the same document produce
the same file names in the same order, so the artifacts diff.

`report.md` ends with a paste-ready configuration block listing every operation
that was skipped for a missing value — the fastest path from "12 endpoints
skipped" to "12 endpoints explored".

## Redaction

Headers, bodies and parameter values pass through `module-http`'s `Redactor`
before they reach a file, so `forge.http.logging.redact-headers` and
`redact-json-fields` govern both request logs and exploration artifacts.
Configure credential field names once, in the module that already owned them.

A parameter named like a credential — an API key in a query string — is masked
too, by asking the redactor whether it would mask a field of that name.

## Limits of v1

- **No request bodies.** An operation that requires one is skipped with a
  reason. Synthesizing a body means guessing at business meaning, and a wrong
  guess against a write endpoint is the damage the safety policy exists to
  prevent.
- **No state between calls.** Each operation is explored independently. There
  is no value extraction from responses, no producer/consumer inference, no
  request sequencing and no replay. The observation model is shaped for all
  four — see `ApiObservation` — but none is implemented.
- **No fuzzing.** Values are the document's own or a deterministic default.
  Nothing is mutated to provoke failure.
- **Optional query parameters are omitted** unless configured. Inventing
  `?status=testforge` would change what the endpoint returns, and the run is
  meant to record its default behaviour.
- **Header and cookie parameters are not filled.** They belong to the
  environment, and `ApiClient` already owns them.
- **Contract checking is breadth-first**, not full JSON Schema validation. It
  covers the six kinds in `MismatchKind`. When a project needs complete schema
  validation of a specific payload, `module-contract` is the tool.
- **`allOf` / `oneOf` / `anyOf` are not composed** during checking; a response
  described only through composition is checked shallowly.

## Agent notes

- Reuse, do not re-implement: the parser, spec registry and swagger model come
  from `module-api-discovery`; the HTTP client and redaction from
  `module-http`. There must never be a second parser or a second client.
- Spring appears only in `TestForgeApiExplorerAutoConfiguration` and
  `ApiExplorerProperties`. Everything else is plain Java with a constructor —
  that is what makes the pipeline testable without a server.
- `ExchangeExecutor` is an interface on purpose. A replay stage supplies its
  own; tests already do.
- One failing operation must never end a run. Every failure becomes an
  observation; the loop always gets one back.
- Generated values are constants, never random. The artifacts have to diff.
- Nothing reaches a file except through `ObservationFactory`, which is the only
  place that decides what is safe to write down.
