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
expectation that declaration implies. Cases fall into three groups, and the
difference between them is the difference between a finding and a false
accusation:

- **schema-proven invalid** (`REJECT`) — a declared constraint forbids the
  value. Accepting it is `OVER_PERMISSIVE`.
- **valid boundary** (`ACCEPT`) — the value is legal, if extreme. Refusing it
  is `OVER_STRICT`.
- **robustness probe** (`UNSPECIFIED`) — the document says nothing. Only a
  crash or an echo is a finding; the status code proves nothing.

Which group a case lands in depends on the schema, never on the mutation:

| The document says | The case sends | It should |
|---|---|---|
| `maxLength: 8` | nine characters | reject |
| `maxLength: 8` | exactly eight | accept |
| `minimum: 1` | `0` | reject |
| `enum: [asc, desc]` | something else | reject |
| `format: date` | `2024-13-45` | reject |
| `required: true` (query) | nothing | reject |
| `exclusiveMinimum: 1` | `1` | reject |
| `exclusiveMinimum: 1` | `2` | accept |
| `multipleOf: 5` | `11` | reject |
| `nullable: false` (body) | `null` | reject |
| `minItems: 1` | `[]` | reject |
| **no** `maxLength` | 4096 characters | either |
| **no** `maximum` | a huge number | either |
| nothing in particular | empty string, unicode, structural characters | either |

The last three rows are the ones that matter most. A long string against a
schema that declares no length breaks no promise, so reporting the service for
accepting it would be this module's bug, not the service's.

## Request bodies

`application/json` request bodies are fuzzed the same way, addressed by JSON
path:

```
createUser/body:$.profile.age/BELOW_MINIMUM
createUser/body:$.name/OMITTED_REQUIRED
createUser/body:$.tags/TOO_MANY_ITEMS
```

Each case starts from a **baseline body built to satisfy every constraint the
schema declares** — required and optional fields, nested objects, arrays sized
to `minItems`, numbers nudged onto `multipleOf` and past exclusive bounds,
strings long enough for `minLength` and matching `format`. Then exactly one
field is changed. A case claims one field is wrong; that claim is only true if
everything else was right.

Supported: `object`, nested objects, `array` with `minItems`/`maxItems` and
item types, `string` with `minLength`/`maxLength`/`pattern`/`format`,
`integer`/`number` with `minimum`/`maximum`/`exclusiveMinimum`/
`exclusiveMaximum`/`multipleOf`, `boolean`, `enum`, `required`, `nullable`, and
`allOf` (merged into one object).

Deliberately not supported, and reported rather than guessed at:

- `oneOf`/`anyOf` at the root — no single baseline can be proven valid, so the
  operation is skipped with that reason. Nested branches get a baseline from
  the first alternative and produce **no cases**: a value invalid for the
  chosen branch may be valid for another.
- Media types other than JSON — skipped with the declared types named.
- Schemas nothing can satisfy — a `pattern` no generated candidate matches
  skips the operation rather than sending a body the service would reject
  anyway, which would make every verdict meaningless.

## The control request

Before any mutation, one **fully valid** request is sent per operation, built
from the same schema and the same configured values. Its answer decides whether
anything the mutations produce can be interpreted:

| Control outcome | Meaning | Cases below it |
|---|---|---|
| `ACCEPTED` | 2xx — the operation is reachable with valid data | interpreted |
| `REJECTED` | 400/422 — the service refuses data the document calls valid | inconclusive |
| `BLOCKED` | 401, 403, 429 or a redirect — the request never reached validation | inconclusive |
| `FAILED` | 5xx — the endpoint is broken regardless of input | inconclusive |
| `UNREACHABLE` | the control never completed | not fuzzed |

This is the difference between v1.1 and v1.2. An endpoint behind authentication
answers `401` to valid data and `401` to invalid data; v1.1 read the second
response alone and called it a passing validation case. A page of green results
meant the door was locked.

One control per operation, per run — and only for operations that will actually
be fuzzed, so replaying a single case does not probe the rest of the document.

## Verdicts and evidence

The verdict says one thing only: what can be concluded about **validation**.

| Verdict | Meaning |
|---|---|
| `PASSED` | The service treated the value the way the document implies. |
| `OVER_PERMISSIVE` | A value the document forbids was accepted. |
| `OVER_STRICT` | A value the document permits was refused. |
| `INCONCLUSIVE` | Nothing can be concluded — the control was not accepted, the response came from infrastructure, or the service crashed. |
| `NOT_APPLICABLE` | The case does not apply to this baseline. |

Everything else is **evidence**: an independent fact about the response,
recorded whatever the verdict concluded.

| Evidence | Meaning |
|---|---|
| `SERVER_ERROR` | 5xx. Always reported; never a validation verdict. |
| `TRANSPORT_FAILURE` | The request did not complete. |
| `UNDOCUMENTED_RESPONSE` | A status or shape the document never describes. |
| `INPUT_REFLECTED` | The value came back verbatim. |
| `INFRASTRUCTURE_RESPONSE` | 401/403/429/3xx — the answer did not come from validation. |
| `CONTROL_NOT_ACCEPTED` | The operation's control request failed. |

v1.1 folded all of these into one enum and ranked them, so a `500` that also
echoed the input reported only the `500`. Now a response with three problems
reports three.

Only `SERVER_ERROR` and `TRANSPORT_FAILURE` fail the build by default.

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

## Constraint coverage

Every operation reports which of the document's promises the run actually
tested:

```
### POST /users
- control: 201 ACCEPTED
- cases: 18, findings: 2, inconclusive: 0
- constraints: 12 declared, 10 exercised

  exercised:
  - $.age maximum
  - $.age minimum
  - $.name maxLength
  ...

  not exercised:
  - $.payload oneOf
  - $.code pattern
```

Listed, not scored. A percentage would invite comparing APIs that declare
wildly different amounts and would reward a vague document for being vague.
What a reader needs is the second list: that is where the run is blind.

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

Each finding also gets a manifest in `<spec>/reproduction.json`: the case id,
seed, operation, mutation, JSON path, expectation, the control status it was
interpreted against, the fuzz status, and a **fingerprint of the document**.
That last field is the one that matters months later — a replay against a
document that has since changed is not a replay, and without the fingerprint
nobody notices. Resolved inputs are redacted like everything else; no manifest
ever carries a credential.

## Safety

This is the one module in the repository that deliberately sends bad data at a
running service, so the gates are the explorer's, unchanged:

- nothing exists unless `forge.api-fuzz.enabled=true`;
- `GET`, `HEAD` and `OPTIONS` only, unless **both** the method is listed and
  `allow-unsafe-methods=true`;
- capped per operation and in total, sequential, never concurrent;
- a request body is only ever sent to a method that passed both gates. Adding
  body generation did **not** make `POST`, `PUT`, `PATCH` or `DELETE`
  reachable; a body is built for them and then never sent unless the project
  opted in.

Point it at a staging environment you own. `exclude-paths` narrows a large
document; it is not a safety mechanism.

## Limits of v1.1

- **One field per case**, so findings stay attributable. Combinations — an
  invalid name *and* an invalid age — are not generated.
- **Bodies are JSON only**, and only where a valid baseline exists.
- **No state between calls**, so anything reachable only after a `POST` is out
  of reach. That is the stateful work recorded in `BACKLOG.md`, and it is what
  would make this module find deep defects rather than surface ones.
- **Cases come from constraints**, so an operation whose fields are declared as
  bare strings produces blunt probes and few provable cases. That is honest:
  the module can only test what was written down.
- **Numeric neighbours are whole units.** The value next to an exclusive bound
  is `bound + 1`, not the smallest representable step.
- **The control is one request, not a warm-up.** An endpoint that needs prior
  state — a resource created by another call — will refuse the control and be
  reported inconclusive rather than fuzzed. That is the stateful work in
  `BACKLOG.md`.
- **No replay engine.** The manifest records what was done; re-running is still
  `only-cases` plus the seed.
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
- `FuzzExpectation` belongs to the case, never to the kind. v1 attached it to
  the kind and quietly produced false findings; if a mutation's meaning ever
  depends only on its name again, that bug is back.
- The baseline body must satisfy the schema. If it cannot, skip the operation
  with the reason — never send a plausible-looking guess. `BaselineSelfCheck`
  verifies this before the control goes out; it already caught a generated
  parameter longer than its own `maxLength`.
- A verdict is about validation. A crash, an echo and an undocumented shape are
  evidence, never verdicts. If they ever compete for one slot again, the
  strongest will start erasing the rest.
- `INCONCLUSIVE` is a feature. A fuzzer that always concludes something is a
  fuzzer that sometimes lies.
