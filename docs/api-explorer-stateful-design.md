# Stateful exploration in module-api-explorer

Status: step 1 shipped (declarative extraction). Steps 2–4 are planned.

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

## Step 2 — proposed links (planned)

Infer candidate rules from the document: a response property named like a
consumer's path parameter (`id` in `GET /orders` items ↔ `{orderId}` in
`GET /orders/{orderId}`). Candidates are written to the report as YAML ready to
paste into `extract`, never applied automatically.

## Step 3 — write chains (planned)

With extraction in place, a `POST` can produce the id a `GET`/`DELETE`
consumes. Still behind both write keys; request bodies come only from
configured templates (module-data `TemplateRenderer`), never synthesized.

## Step 4 — replay (planned)

A second `ExchangeExecutor` that serves recorded exchanges, so a failing chain
can be reproduced without the environment.
