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
| `uniqueItems: true` | the same element twice | reject |
| `additionalProperties: false` | an undeclared property | reject |
| `readOnly: true` (body) | the property, in a request | either |
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

## Objects, arrays and compositions

A few constraints only mean something in a body, and each has one rule.

`uniqueItems` is built into the baseline before it is tested against: an array
declared unique with `minItems: 2` gets two *different* elements, and the
mutation repeats one. Filling it with copies — which is what v1.3 did — made
the control itself violate the document and every case beneath it meaningless.

`additionalProperties: false` earns an undeclared-property case. Nothing else
does: an absent `additionalProperties` permits extras outright, and a
schema-valued one constrains rather than bans them, so a service accepting an
extra field there is obeying its document.

`readOnly` properties are left out of the control, because that is what the
document asks for, and put back by one probe. OpenAPI phrases it as "SHOULD
NOT", so a service that quietly accepts one is not breaking a promise — a crash
still is. A property that is both `required` and `readOnly` describes a request
nobody can send, and is reported as unsupported rather than guessed at.

`oneOf` and `anyOf` are the interesting case, because they break the assumption
every `REJECT` rests on. The baseline has to pick a branch, and a value invalid
for that branch may be valid under a sibling — so the document was never broken,
and a service accepting it is correct. Reporting that as `OVER_PERMISSIVE` is
worse than reporting nothing.

So a composition is fuzzed only when the branch is provably the only one in
play. A `discriminator` does that, but only when the discriminating property is
pinned to a single value in the chosen branch and every sibling provably
excludes it:

```yaml
method:
  discriminator: { propertyName: kind }
  oneOf:
    - properties: { kind: { enum: [card] }, card: { minLength: 4 } }   # chosen
    - properties: { kind: { enum: [iban] }, iban: { type: string } }
```

Here `$.method.card` is fuzzable and `$.method.kind` is not — changing the
discriminator hands the request to a schema the case was never derived from.
Without that proof the whole subtree is reported unsupported, with the reason.

## Parameter serialization

An array parameter is assembled according to its own `style` and `explode`,
because a serialization defect that reaches the wire is indistinguishable from a
validation finding. A `tags` array sent as the literal string `testforge` is
malformed before it arrives; whatever comes back says nothing about the service.

| Declared | Sent as |
|---|---|
| `style: form, explode: false` | `?status=open,closed` |
| `style: spaceDelimited` | `?status=open%20closed` |
| `style: pipeDelimited` | `?status=open|closed` |
| `style: simple` (path) | `/reports/open,closed` |
| `style: form, explode: true` | **refused** — repeats the name per element |
| `style: deepObject`, object-valued | **refused** |
| `style: label`, `style: matrix` | **refused** — rewrites the path segment |

A refusal is not silence: every constraint the parameter declared appears in the
report's unsupported layer with the reason. Array cases come in two layers —
the size constraints of the array itself, and the constraints of one element
mutated in place, reported as `query:status[0]`.

## Protocol mutations

Four cases attack the envelope rather than a value inside it:

```
createUser/protocol/MALFORMED_JSON
createUser/protocol/UNSUPPORTED_CONTENT_TYPE
createUser/protocol/MISSING_CONTENT_TYPE
createUser/protocol/EMPTY_BODY
```

They reach a different layer of a service — the body parser, content
negotiation, the error handler — code nobody on the team wrote and few have
read, which is where a stack trace tends to escape into a response body.

Two of them can claim a `REJECT`: broken JSON cannot be an instance of any
schema, and a media type the operation does not list is not described at all. A
missing `Content-Type` is a probe, because a recipient is entitled to guess —
and if the HTTP client puts one back, the case reports `NOT_APPLICABLE` with
what was actually sent rather than scoring a request nobody designed.

They are counted separately from schema mutations and exercise no declared
constraint, so coverage keeps meaning what it says. They are also added *after*
the case budget rather than into it: at most four, and turning them off must not
change which schema constraints a run tested. `protocol-mutations: false` turns
them off.

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
    protocol-mutations: true    # malformed JSON, wrong Content-Type, empty body
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
- cases: 22, findings: 2, inconclusive: 0
- constraints: 14 declared, 10 exercised, 2 unsupported, 2 not exercised

  exercised:
  - $.age maximum
  - $.age minimum
  - $.name maxLength
  ...

  unsupported — no mutation here could be proven invalid:
  - $.payment oneOf — the oneOf declares no discriminator, so a value invalid
    for the chosen branch may still satisfy another and no rejection can be
    proven
  - query:owner minItems — style 'form' with explode=true repeats the parameter
    name per element, and the request model carries one value per name

  not exercised:
  - $.code pattern

  mutation outcomes: 18 schema, 4 protocol
  - expectation REJECT: 14
  - expectation ACCEPT: 5
  - expectation UNSPECIFIED: 3
  - verdict PASSED: 20
  - verdict OVER_PERMISSIVE: 2
  - evidence SERVER_ERROR: 1
```

Four layers, listed rather than scored. A percentage would invite comparing
APIs that declare wildly different amounts and would reward a vague document
for being vague.

The third layer is the one worth reading. "Not exercised" and "unsupported"
look alike in a count and mean opposite things: the first is a constraint the
case budget did not reach, the second is one nothing could honestly attack, and
only the reason tells them apart. Faced with a construct it cannot mutate, the
module has two options — say so there, or guess — and a guess produces a
request the document never described, which makes whatever comes back
unattributable.

There is deliberately no aggregate hardening score. Any single number averages a
crash together with an unanswered probe, and the three counts a reader actually
needs — how many cases were schema-proven invalid, how many of those were
accepted anyway, and what crashed — are three numbers.

## Confirmation and minimization

A finding is only useful if an engineer can act on it. Two optional phases turn
"this case failed once" into a reproducer:

**Confirmation** re-sends the finding's request a bounded number of times and
reports what happened:

| Reproducibility | Meaning |
|---|---|
| `NOT_CONFIRMED` | Confirmation was off — the default |
| `REPRODUCIBLE` | Every attempt showed the same finding |
| `FLAKY` | Some did, some did not — reported, never hidden |
| `DISAPPEARED` | No attempt showed it again |
| `NOT_ATTEMPTED` | A write method without the separate opt-in |

The control is re-checked between attempts. A mutant can leave the backend
unable to serve even valid requests — a created resource, a tripped breaker, a
filled quota — and without that check the remaining attempts read as a
confident `FLAKY` that says nothing about the defect.

**Minimization** then strips the request down to the smallest one that still
shows the same finding: optional fields removed, arrays shrunk to `minItems`,
optional query parameters dropped, and unrelated values shortened to the
smallest form their own schema still allows. Required fields, the target itself, and
every other declared constraint are left alone, so each candidate stays "the
valid baseline except the one mutation". A `oneOf`/`anyOf` node may be dropped
whole when it is optional, but nothing is ever removed from *inside* the branch
the baseline picked — that would produce a request the document never
described.

```
original: {"title":"aaa","priority":6,"note":"aaaa","tags":["aaaa"]}
minimal:  {"title":"aaa","priority":6}
4 → 2 fields in 2 attempts
```

"Still the same finding" is decided by a `FindingSignature` — verdict, the
strongest evidence, and the status family — not by the status code alone. A
shrink that accidentally destroys the finding is rejected rather than
celebrated.

### What it costs

Both phases are **off by default**, so v1.3 adds exactly zero requests until a
project asks for it. When enabled, the worst case per run is:

```
findings × (confirmation-runs + max-shrink-attempts)
```

on top of one control per fuzzed operation and one request per case. Nothing is
retried in a loop and nothing searches: the shrink order is fixed and the
budget is a hard stop.

Repeating a write method needs its own key. Sending a `POST` once because two
gates were opened is not consent to send it four more times:

```yaml
forge:
  api-fuzz:
    confirmation-runs: 2
    max-shrink-attempts: 25
    allow-unsafe-confirmation: false   # required for POST/PUT/PATCH/DELETE
```

## The reproduction folder

Every finding gets one:

```
build/api-fuzz/reproductions/<case-id>/
  manifest.json    case id, seed, spec fingerprint, control, verdict, sizes
  request.json     the minimal request, redacted
  reproduce.md     seven sections, in the order an engineer reads them
```

`reproduce.md` answers: which case, what was sent, what the document promised,
what the service answered, whether it is stable, whether it is minimized, and
how to run exactly this case again.

There is deliberately **no `curl`**. The request went out through `ApiClient`
with the project's authentication, correlation and retry; a command line that
silently drops all of that fails differently and sends the reader hunting for a
bug in the wrong place.

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

## Limits

- **One field per case**, so findings stay attributable. Combinations — an
  invalid name *and* an invalid age — are not generated.
- **Bodies are JSON only**, and only where a valid baseline exists.
- **A composition without a usable discriminator is not fuzzed at all**, not
  even shallowly. Proving a mutant invalid against every branch would need a
  full JSON Schema validator, and a wrong proof here produces exactly the
  confident false finding the module exists to avoid.
- **Array parameters need a single-valued wire form.** `explode: true` — the
  OpenAPI default for a query array — repeats the parameter name per element,
  and the shared request model carries one value per name. Those parameters are
  reported unsupported rather than comma-joined into a shape the document never
  described. Multi-valued parameters are the next increment.
- **`additionalProperties` is not carried across an `allOf`.** Each subschema
  sees only its own properties, so a merged `false` would forbid fields a
  sibling declares and manufacture findings out of a JSON Schema subtlety.
- **Protocol mutations are four fixed cases**, not a transport fuzzer. Chunked
  encoding, header injection, oversized bodies and HTTP smuggling are a
  different tool with a different threat model.
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
- **Minimization is greedy, not exhaustive.** One pass in a fixed order, one
  request per candidate, a hard budget. It will not find the globally smallest
  payload and does not try to.
- **Value shrinking is narrow.** Only values this module invented — an
  unconstrained long string, an arbitrary huge number — are reduced. A case
  derived from a declared bound is already minimal.
- **Confirmation is not statistics.** Two or three attempts distinguish stable
  from intermittent; they do not measure a rate.
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
- Every shrink candidate must remain the valid baseline except the one
  mutation. Required fields and `minItems` are floors, not suggestions —
  breaking a second constraint turns one finding into an unattributable mess.
- Repeating a request is a separate consent from sending it. Keep
  `allow-unsafe-confirmation` distinct from `allow-unsafe-methods`.
- A flaky finding stays in the report. The intermittent 500 is usually the
  interesting one.
- A constraint the module cannot attack goes in `unsupported` with a reason,
  never in `unexercised` and never silently absent. The reason is the whole
  value of the layer; an entry without one is worse than no entry.
- Never mutate inside a `oneOf`/`anyOf` unless the branch is provably pinned.
  The check lives in `Compositions#choose`, and every relaxation of it is a new
  class of false `OVER_PERMISSIVE`.
- Protocol cases carry no constraint and are added after the case budget, not
  into it. Both properties keep coverage honest; changing either makes the
  numbers mean something other than what they say.
- The baseline honours `uniqueItems` and omits `readOnly` before anything is
  mutated. A control that violates the document is the one failure mode that
  invalidates an entire run silently.
- Serialization is decided from the parameter, never from the value. If a style
  has no faithful single-valued form, produce nothing — a comma-joined guess
  turns this module's defect into the service's.
