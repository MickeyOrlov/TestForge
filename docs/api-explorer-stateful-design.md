# Stateful exploration in module-api-explorer

Status: all four steps shipped — extraction, proposed links, write chains, replay.

## Problem

V1 explores every operation on its own. Most interesting operations need a
value only another response can provide — `GET /orders/{id}` needs an `id`
that exists. Without one the explorer either skips the call (no value) or
sends the document's example (`id: 123`), which rarely exists on the
environment, and reports a 404 that says nothing about the API.

## Principles

- **Explicit before inferred.** A value flows from one call to another only
  because a rule says so (step 1). Inference (step 2) may *propose* rules; it
  never silently invents data flow.
- **Read-only first.** The common chain — list, then item — needs no write
  method at all. Write methods stay behind the existing two keys
  (`methods` + `allow-unsafe-methods`), and request bodies stay out until a
  rule can supply them.
- **One run, one store.** Extracted values live for one spec's run; nothing
  carries over between specs, runs or environments.
- **Report the chain, not the data.** The report says whether each rule
  worked and why not; extracted values appear only where they were used, in
  the consuming observation's parameter bindings.
- **Same pipeline.** Extraction plugs in as a `ValueSource` and an operation
  order; planning, safety, execution, checking and artifacts are unchanged.

## Step 1 — declarative extraction (shipped)

```yaml
forge:
  api-explorer:
    extract:
      - from: listOrders            # producer operationId
        json-path: "$[0].id"        # core JsonPath: $.a.b, items[0].id, $[0].id
        into: [getOrder.id]         # operationId.parameter, or a bare parameter name
```

- Producers run before the operations they feed; otherwise the selection
  order is kept. A cycle between rules keeps the original order.
- Values are read from the raw response (before redaction and truncation),
  only from a 2xx JSON body, and only when the path hits a single value.
- Resolution order: configured value → extracted value → example → default →
  enum → generated.
- Each rule ends in one status: `EXTRACTED`, `PRODUCER_NOT_RUN`,
  `PRODUCER_FAILED`, `NOT_JSON`, `NOT_FOUND`, `NOT_A_VALUE` — rendered in the
  report's *Value extraction* table.
- Values travel through REST Assured path/query parameter encoding, so a
  value such as `../admin` cannot change the request target.

## Step 2 — proposed links (shipped)

The document alone (no extra calls) suggests rules for every path parameter
that nothing configures or feeds yet. Producers are callable `GET`s without
path parameters; two signals, strongest first:

1. **parent collection** — `GET /tasks` for `GET /tasks/{taskId}`: the
   response's `taskId`, else its `id`;
2. **same name** — any such `GET` whose response holds a scalar named exactly
   like the parameter (`/me` → `profile.tenantId` for `/tenants/{tenantId}/users`).

The shortest JSON path wins (arrays as `[0]`, depth ≤ 4). Proposals go into
the report's *Proposed extraction rules* block as YAML with the reason next
to each, and into `report.json`. They are never applied: a wrong guess would
send a real id to the wrong endpoint.

## Step 3 — write chains (shipped)

```yaml
forge:
  api-explorer:
    methods: [GET, POST, DELETE]
    allow-unsafe-methods: true          # the second key; never on a shared environment
    bodies:
      createTask: '{"title": "testforge-{{unique}}"}'
    extract:
      - from: createTask
        json-path: "$.id"
        into: [getTask.taskId, deleteTask.taskId]
```

- Write methods still need both keys; templates and rules change nothing
  without them.
- Request bodies come only from `bodies.<operationId>`; an operation that
  requires a body and has no template is skipped with the property to set.
  `{{unique}}` becomes a fresh token per request, so reruns never collide.
  Kept inside the module (no `module-data` dependency): one placeholder is all
  a payload needs to stay unique.
- `DELETE` runs after every other operation, so a chain reads what it created
  before removing it.
- The report states how many write requests were sent and to which base URL.
- Bodies go through the same redaction as every other observed request.

## Step 4 — replay (shipped)

`forge.api-explorer.replay-from: <an earlier run's output-dir>` swaps the
executor for `ReplayExchangeExecutor`: every request is answered from the
recorded observations, nothing touches the network, and the rest of the
pipeline (planning, extraction, checking, report) runs unchanged.

- A request matches a recording by method plus resolved path and query; the
  base URL is not part of its identity.
- What a recording cannot faithfully give back fails loudly instead: an
  unrecorded request, a recorded failure, and a body truncated at
  `max-body-chars` (raise it and record again). Redacted values stay `***`.
- Replaying into the recording's own directory is refused; an empty recording
  is refused.
- Write chains replay deterministically: the recorded `POST` returns the
  recorded id, so the following `GET`/`DELETE` match their recordings even
  though `{{unique}}` renders a new body.
