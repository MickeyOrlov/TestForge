# Toolbox decisions: own code next to a proven tool

[docs/toolbox.md](toolbox.md) listed three places where TestForge has its own
code although an established open-source tool exists. Each was checked on real
inputs in November 2026 — keep, wrap or replace — in the manner of
[api-fuzz-prototype-analysis.md](api-fuzz-prototype-analysis.md). The harness
and the probes live outside the repository (`~/Documents/quality-dogfood`).

| Own code | Tool it was measured against | Decision |
|---|---|---|
| `module-api-codegen` | OpenAPI Generator 7.27.0 | **keep**, bounded; OpenAPI Generator alongside for rich typing |
| `module-security` artifact secret scan | gitleaks 8.30.1 | **combine**: keep the own scan, add gitleaks' report as an input |
| `module-api-explorer` walker | EvoMaster 6.2.0 | **keep, frozen**: defect hunting belongs to fuzzers |

## 1. `module-api-codegen` vs OpenAPI Generator

Both generated Java REST Assured clients from spring-petclinic-rest's document
(`openapi.yml`, 37 operations). OpenAPI Generator: `-g java --library
rest-assured`, Jackson, Jakarta, `useRecords=true`.

| | `module-api-codegen` | OpenAPI Generator |
|---|---|---|
| Compiles | **yes** (its own compile check) | **no** — it copies `x-field-extra-annotation`, which names petclinic's own validator class, into the client |
| Size | 27 files, 1151 lines | 36 files, 7444 lines |
| Models | Java records | mutable classes; `useRecords` is not applied by this generator |
| A call | `owners.getOwner(1)` over `ApiClient` — the project's auth, correlation, logging and contract guard apply | its own `RequestSpecBuilder` plumbing, `getOwner().ownerIdPath(1).execute(…)` |
| On top | compile check with file:line errors; "what regeneration changed" for tests (BREAKING / RISKY) | — |
| Ahead | — | typed `oneOf` / `anyOf` and enums (here: `Object` and the wire scalar, with a report warning) |

**Decision: keep.** The module's value is TestForge-specific — clients that
are `ApiClient` calls, records, and a regeneration diff written for the tests
that compile against it — and the established generator did not produce
compiling code for a real document without tuning. Bounds: no race to
OpenAPI Generator's feature list. A project that needs rich `oneOf` / enum
typing uses OpenAPI Generator alongside; its `rest-assured` clients take a
`Supplier<RequestSpecBuilder>`, and a builder can start from the project's
spec with `new RequestSpecBuilder().addRequestSpecification(apiClient.request())`.

## 2. Artifact secret scan vs gitleaks

Eight fake leaks planted in an artifacts folder (fake values of the right
shape, never real secrets), scanned by `SecurityArtifactScanner` with the
project's role token registered, and by `gitleaks dir --max-archive-depth 2`.

| Leak | `module-security` | gitleaks |
|---|---|---|
| The project's role token in an HTTP log | **found** | missed — no known shape |
| The same token inside a zipped Playwright-style trace | **found** | missed |
| JWT | found | found |
| `db.password: …` | **found** | missed — low entropy |
| AWS access key | missed | **found** |
| GitHub token | missed | **found** |
| Stripe key | missed | **found** |
| Slack webhook | missed | **found** |

Each found four or five and they overlap on one; together, eight of eight.
The own scan knows what no generic scanner can — this run's actual
credentials — and gitleaks knows hundreds of credential shapes the own scan's
three patterns do not.

**Decision: combine.** Keep the own scan (exact known secrets, archives,
before upload); read a gitleaks report as a second input instead of growing a
pattern catalogue here. Until that reader exists, run gitleaks next to it in
CI. Found on the way and fixed: a finding in `app.properties` was reported as
`/{value}`, because the file-name mask let through extensions of at most 8
letters; it now allows 16 (`.properties`, `.network`), still masking names
with digits or IDs.

## 3. `module-api-explorer` walker vs EvoMaster

From the petclinic field test
([research/quality-dogfood-petclinic.md](research/quality-dogfood-petclinic.md)):

| | Explorer (GET only) | EvoMaster, black box, 3 minutes |
|---|---|---|
| HTTP calls | 26 with the functional tests | 1879 |
| Operations answering 500 | 1 | 41 |
| Operations with a response schema violation | 2 | 36 |
| Tests for the findings | — | 134 JUnit 5 |
| Writes | never, unless two keys are set | always, in black-box mode |
| Same requests every run | yes — artifacts diff | no — a search |
| Roles | access matrix per configured role | not in black-box mode |

**Decision: keep, frozen.** As a defect hunter the walker is a partial wheel
and loses by an order of magnitude; that job belongs to EvoMaster and
Schemathesis, whose results `module-quality` reads (WFC report,
`module-api-fuzz`). What the walker is for has no off-the-shelf equivalent:
a deterministic, safe-by-default pass whose output diffs between runs, the
runtime contract report, the access matrix per role, the contract guard, the
stub contract, replay. Bounds: no new request-generation or search strategies
in the walker — a gap in finding defects is answered by a fuzzer's report, not
by growing it.

## What follows

- A `module-quality` reader for gitleaks' JSON report (or SARIF), next to the
  own scan — the one open action of the three.
- docs/toolbox.md: the three rows are no longer `own — review`.
