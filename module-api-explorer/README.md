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

A declared media **range** covers the response it describes: `*/*` matches any
content type and `application/*` matches any `application` subtype. This matters
because springdoc documents `*/*` for every handler without an explicit
`produces`. An exact declaration is still preferred over a range in the same
response, so the body is checked against the schema of the specific one.

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

## Contract guard: the contract checked in every functional test

The explorer runs on its own schedule. The guard needs no run at all: with it
on, every response `ApiClient` receives in an ordinary test is checked against
the same document, with the same checks.

```yaml
forge:
  api-discovery:
    specs:
      orders:
        location: classpath:/openapi/orders.yaml
  contract-guard:
    enabled: true
    mode: record     # default: collect, log each new finding once, publish to the run index
    # mode: fail     # fail the call that received a drifted response
    specs: [orders]  # optional; default every registered spec
```

- Independent of `forge.api-explorer.enabled`; sends no extra requests.
- A call the document does not describe is reported as `UNDOCUMENTED_OPERATION`.
- `record` mode publishes `contract-guard.json` with `healthy: false` when
  anything drifted, so it shows under *Needs attention* in `summary.md`.
- `fail` mode throws an `AssertionError` naming the kind and JSON path, e.g.
  `MISSING_REQUIRED_FIELD at $.items[].status`.
- Paths are matched against the document relative to `forge.http.base-url`; a
  literal path wins over a templated one. Only responses from that base URL
  are checked: a call the same `ApiClient` sends to another service
  (`request("payments")`, a downstream mock) is not this document's business.
- springdoc declares a `*/*` body for every status of a `ResponseEntity`
  handler, a 404 from `ResponseEntity.notFound().build()` included. Such a
  response has no `Content-Type`, and the guard reports
  `UNEXPECTED_CONTENT_TYPE … got no content type`: the document promises a
  body the service never sends. Declare the bodiless status without content
  (`@ApiResponse(responseCode = "404", content = @Content)`) rather than
  accepting the finding.

Start in `record` mode on an existing suite, read the first report, then
switch the CI profile to `fail`.

The guard also knows which documented operations the suite's calls reached:
`api-coverage.md` in the run index lists every operation no test called and
how often each called one was hit (`guard.coverage()` in a test). Report-only
(`healthy: true`, `uncalled` count as metadata): an untested `DELETE` is a
gap to read about, not a failed build.

## Stub contract: mocks that cannot lie about the downstream

A WireMock stub that returns a field the real service never sends — or drops
one it always does — makes a test pass against a service that does not exist.
Register the downstream's own document and hold the stubs to it:

```yaml
forge:
  api-discovery:
    specs:
      payments:
        location: classpath:/openapi/payments.yaml   # the downstream's document
  stub-contract:
    enabled: true
    specs: [payments]               # optional; default every registered spec
    strip-prefix: /payments-service # optional; the mock URL prefix the document does not know
```

```java
@Autowired StubContractChecker stubContract;

stubContract.assertHonoured(wireMock);                 // every stub on the server, e.g. the defaults
stubContract.assertHonoured(scope.stub(get(...)...));  // one scoped stub (module-mock)
```

- The same matching and checks as the contract guard: required fields,
  types, documented statuses, `UNDOCUMENTED_OPERATION` for a stubbed call the
  service does not have.
- What a stub does not state is listed as *not checked*, never passed: URL
  regex matchers, faults, body files, templated bodies.
- Reads stub definitions only, sends nothing. Needs WireMock on the classpath
  (`module-mock` brings it); inert unless `forge.stub-contract.enabled=true`.

## Access matrix: who may call what

Every operation of the document, called once per role, each status held to a
declared rule. A role that gets `200` where it should get `403` is a hole in
the product's authorization — the bug functional tests rarely look for,
because each test only checks the role it was written for.

```yaml
forge:
  api-discovery:
    specs:
      orders:
        location: classpath:/openapi/orders.yaml
  api-explorer:
    parameters:
      defaults:
        orderId: "o-1"          # path values, exactly as for exploration
  auth:
    enabled: true
    roles: { admin: ..., member: ... }
  access-matrix:
    enabled: true
    roles: [admin, member, anonymous]   # default: the roles the rules name
    expect:
      admin: allow
      member: allow
      anonymous: "401"
    operations:
      readAuditLog:
        member: deny
    fail-on-undeclared: false            # default
```

```java
@Autowired AccessMatrixRunner matrix;

@Test
void everyRoleGetsWhatItShould() {
    matrix.assertHealthy();
}
```

- Rules: `allow` (any status below 500 except 401/403 — a 404 for a missing
  id still means the caller was let in), `deny` (401 or 403), or explicit
  statuses and classes: `404`, `401,403,404`, `2xx`. A bad rule fails the
  context with every bad key listed.
- `anonymous` is reserved: no token, even with `forge.auth.default-role`.
  A role `forge.auth.roles` does not configure fails before any request.
- Requests are planned as exploration plans them and obey the same method
  policy: GET/HEAD/OPTIONS only, unless both write keys are set. Skipped cells
  say why (unsafe method, missing parameter value).
- A 5xx or a failed call is an *error*, not a pass: it says nothing about
  access. Violations and errors fail `assertHealthy()`; undeclared cells fail
  it only with `fail-on-undeclared`.
- `access-matrix.md` lists violations first, then the matrix, then
  *Proposed expectations* — the YAML the run observed for undeclared cells.
  Review it before pasting: an observed `allow` may be exactly the hole.
- Only statuses are recorded, never bodies. Both reports are registered in
  the run index with `healthy` and `violations`.
- Independent of `forge.api-explorer.enabled`; inert unless
  `forge.access-matrix.enabled=true`.

## Stateful exploration: values from one call feeding the next

Most item endpoints need an id that exists. Tell the explorer where to find
one, and the call that consumes it runs after the call that produces it:

```yaml
forge:
  api-explorer:
    extract:
      - from: listTasks               # producer operationId
        json-path: "$.items[0].id"    # $.a.b, items[0].id, $[0].id on a root array
        into: [getTask.taskId]        # operationId.parameter, or a bare name for every operation
```

- Read-only chains need no write method: list, then item.
- Values come from the raw 2xx JSON response and must be a single value.
- A configured `parameters` value still wins; an extracted value beats the
  document's example (which rarely exists on the environment).
- The report's *Value extraction* table says, per rule, whether it worked
  and why not — never the value itself.
- Values are sent through REST Assured parameter encoding, so a hostile
  value cannot change the request target.

You rarely need to write the first rules by hand: every run reads the
document for likely links (a list endpoint one level above an item endpoint,
or a response property named like the parameter) and writes them to the
report under *Proposed extraction rules*, ready to paste:

```yaml
forge:
  api-explorer:
    extract:
      - from: listTasks
        json-path: "$.items[0].id"
        into: [getTask.taskId]   # GET /tasks lists the items GET /tasks/{taskId} reads
```

Proposals are never applied on their own — review, paste, run again.

### Write chains: create, read, delete

With both write keys on, a body template is the only way a request body is
sent, and an extracted id ties the chain together:

```yaml
forge:
  api-explorer:
    methods: [GET, POST, DELETE]
    allow-unsafe-methods: true      # never against a shared environment
    bodies:
      createTask: '{"title": "testforge-{{unique}}"}'   # {{unique}}: fresh token per request
    extract:
      - from: createTask
        json-path: "$.id"
        into: [getTask.taskId, deleteTask.taskId]
```

`DELETE` always runs last, after the calls that read what was created. The
report's header says how many write requests went to which environment.
Without a template, an operation that requires a body is skipped and the
report names the property to set.

### Replay: reproduce a run without the environment

Point a run at an earlier run's output directory and it answers every request
from the recorded observations — no network at all:

```yaml
forge:
  api-explorer:
    output-dir: build/api-explorer-replay
    replay-from: build/api-explorer      # the run to reproduce; must be another directory
```

Replay reports show `write requests sent: 0`, even when the recording contains
POST or DELETE observations: replay sends no requests and changes no environment data.

Use it to debug a failing chain or a contract mismatch at your desk. What the
recording cannot give back is reported as a failed call with the reason: an
unrecorded request, a recorded failure, or a body truncated at
`max-body-chars` (raise it and record again). Redacted values stay redacted.

Design: [docs/api-explorer-stateful-design.md](../docs/api-explorer-stateful-design.md).

## Limits of v1

- **No synthesized request bodies.** Bodies come only from
  `forge.api-explorer.bodies` templates; an operation that requires one and has
  no template is skipped with the property to set. Guessing at business
  meaning against a write endpoint is the damage the safety policy exists to
  prevent.
- **State only through explicit rules.** Values flow between calls only via
  `forge.api-explorer.extract` (proposed automatically, applied only once
  configured).
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
