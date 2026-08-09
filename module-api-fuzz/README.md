# module-api-fuzz

Sends deliberately wrong values at an API and reports where the answers stop
matching its own OpenAPI document.

`module-api-discovery` reads the document, `module-api-explorer` runs it, and
this module breaks it on purpose.

## What makes it schema-aware

A generic fuzzer can tell you the service returned `500`. Only a schema-aware
one can tell you the service returned `200` for a value its own document
forbids — which means every consumer generated from that document is built on
a promise nobody keeps.

Every case is derived from what the document declares, and carries the
expectation that declaration implies:

| The document says | The case sends | It should |
|---|---|---|
| `maxLength: 8` | nine characters | reject |
| `maxLength: 8` | exactly eight | accept |
| `minimum: 1` | `0` | reject |
| `enum: [asc, desc]` | something else | reject |
| `format: date` | `2024-13-45` | reject |
| `required: true` (query) | nothing | reject |
| nothing in particular | empty string, unicode, structural characters | either |

The last row is why the module reports crashes and reflections separately from
validation gaps: where the document is silent, only a crash or an echo is a
finding.

## Verdicts

| Verdict | Meaning |
|---|---|
| `SERVER_ERROR` | 5xx. Malformed input should be refused, not fatal. |
| `OVER_PERMISSIVE` | The document forbids this value and the service took it. |
| `UNDOCUMENTED_RESPONSE` | A status or shape the document never describes. |
| `INPUT_REFLECTED` | The value came back verbatim — an escaping question. |
| `OVER_STRICT` | A valid boundary value was refused. |
| `TRANSPORT_FAILURE` | The request never completed. |
| `PASSED` | The service did what the document implies. |

Only `SERVER_ERROR` and `TRANSPORT_FAILURE` fail the build by default. The
others are conversations to have with the service team first: the opening sweep
of an unfamiliar API finds enough of them that failing immediately teaches
people to switch the module off.

## Configuration

Specs come from `forge.api-discovery.specs`; baseline parameter values reuse
the explorer's own `parameters` block, so an id configured once serves both.

```yaml
forge:
  api-fuzz:
    enabled: false              # default; nothing exists until this is true
    seed: 0
    output-dir: build/api-fuzz
    methods: [GET, HEAD, OPTIONS]   # default
    allow-unsafe-methods: false     # required for anything else
    max-operations: 50
    max-cases-per-operation: 20
    only-cases: []              # replay exactly these
    fail-on:
      server-error: true
      transport-failure: true
      over-permissive: false
      undocumented-response: false
      input-reflected: false
```

## Reproducing a finding

Every case has a stable, readable id — `getTask/path:taskId/TOO_LONG` — and the
report prints the configuration that repeats one and nothing else:

```yaml
forge:
  api-fuzz:
    seed: 20260101
    only-cases:
      - "getTask/path:taskId/TOO_LONG"
```

That is what the seed is for. Generation itself is fully deterministic; the
seed decides which subset runs when the case matrix exceeds
`max-cases-per-operation`, so a capped run is still reproducible. Change the
seed on a scheduled job and a different slice gets covered over time.

## Safety

This is the one module in the repository that deliberately sends bad data at a
running service, so the gates are the explorer's, unchanged:

- nothing exists unless `forge.api-fuzz.enabled=true`;
- `GET`, `HEAD` and `OPTIONS` only, unless **both** the method is listed and
  `allow-unsafe-methods=true`;
- capped per operation and in total, sequential, never concurrent;
- no request bodies are ever sent.

Point it at a staging environment you own. `exclude-paths` narrows a large
document; it is not a safety mechanism.

## Limits of v1

- **Parameters only.** Request bodies are not fuzzed, because the explorer does
  not synthesize them — that waits for value extraction.
- **One parameter per case**, so findings stay attributable. Combinations are
  not explored.
- **No state between calls**, so anything reachable only after a `POST` is out
  of reach. That is the stateful work recorded in `BACKLOG.md`, and it is what
  would make this module find deep defects rather than surface ones.
- **No mutation of the document's own valid values** — cases come from
  constraints, so an operation whose parameters are declared as bare strings
  produces blunt cases. That is honest: the module can only test what was
  written down.
- Reflection detection compares decoded JSON string values and raw text. It
  reports an echo; deciding whether it is exploitable is not a test framework's
  job.

## Agent notes

- Everything except case generation and verdicts is the explorer's:
  `OperationSelector`, `SafetyPolicy`, `RequestPlanner`, `ExchangeExecutor`,
  `ResponseContractChecker`, `ObservationFactory`. Never fork one of them.
- Generation must stay deterministic. A random value that finds a bug once and
  never again is worse than no value at all.
- The seed only earns its place because sampling exists. If the budget ever
  stops capping, the seed becomes a lie and should be removed.
- Case ids are the unit of reproduction and appear in the exception message,
  the markdown and the JSON. Do not change their shape casually.
- Only crashes fail the build by default. Widening that is a project's
  decision, not this module's.
