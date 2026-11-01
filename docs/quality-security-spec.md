# Specification: product quality assessment and the security block

Status: **agreed; in progress**. Part A goes first (decided). Step A1 is
shipped as `module-quality`; the step table in section 6 tracks the rest.

## 1. Why

TestForge already produces most of the evidence a team needs to judge a
product: test outcomes, contract and schema drift, fuzz findings, the access
matrix, query budgets, unused stubs, failed-test diagnostics. Each module
reports on its own, in its own file. Nobody answers the two questions a
release decision needs:

1. **How good is this build of the product, and is it better or worse than
   the last accepted one?**
2. **Is it safe — and what exactly did we check to say so?**

This spec adds two capabilities on top of what exists:

- **Part A — quality assessment** (`module-quality`): read what a run
  produced, score the product along a few named dimensions, compare with a
  baseline, gate the build on regressions.
- **Part B — security block** (`module-security`): security checks and tests
  for the system under test, a findings model, and a security assessment that
  feeds Part A as one of its dimensions.

## 2. Principles (apply to both parts)

- **Compose, do not reimplement.** Like `module-api-fuzz` delegates to
  Schemathesis, active security scanning delegates to an established scanner
  (OWASP ZAP). TestForge contributes configuration, safety policy, findings
  normalisation and the gate.
- **Evidence, not opinion.** Every score and every finding links to the
  artifact it came from (`manifest.json` path, test name, request). A number
  without its evidence is not shown.
- **Fail on what is new.** Both parts use the pattern `module-api-fuzz`
  established: a team reads the first report, copies what it accepts into an
  `accepted-*` list with a reason, then turns the gate on. The gate fails on
  regressions and new findings only.
- **Default build stays offline and harmless.** Everything here is inert
  unless enabled; anything that sends traffic runs from an environment profile
  job, never the default build.
- **Never against production, never destructive.** Active checks need two
  keys (enabled + an explicit target allowlist), run only against hosts named
  in configuration, are rate-limited, and never send denial-of-service or
  data-destroying payloads.
- **No secrets or personal data in reports.** Findings name the endpoint,
  header or field, never the value; the existing redaction settings
  (`forge.http.logging.redact-*`) apply.
- **Scores are explainable.** A dimension's score is a small, documented
  formula over counted facts; no weighting the reader cannot reproduce by hand.

## 3. Part A — quality assessment (`module-quality`)

### 3.1 Inputs

The run index `module-reporting` writes (`build/testforge-artifacts/<run-id>/
manifest.json`), plus the JUnit XML results of the same run. Each producing
module already registers its report there; `module-quality` reads them by
`source` / `category` and never re-runs anything.

| Signal | Source today |
|---|---|
| Test outcomes, durations, retries | JUnit XML, failed-test diagnostics |
| API contract mismatches | `module-api-explorer` runtime contract report, contract guard |
| Event payload drift | `module-contract-monitor` |
| Schema compatibility | `module-db-contract` (BREAKING / RISKY / SAFE / UNKNOWN) |
| Mapping drift | `module-db` `SchemaValidator` |
| Robustness | `module-api-fuzz` findings (new vs accepted) |
| Authorization | `module-api-explorer` access matrix |
| Efficiency | `module-db` query budgets and slow statements; HTTP latency from `module-http` logs |
| Test hygiene | `module-mock` unused scoped stubs, `module-data` leftover data, flaky retries |
| Security | Part B findings |

A module that is not used simply contributes no dimension; the report says
which dimensions were not measured instead of scoring them as perfect.

### 3.2 Dimensions and scoring

Each dimension produces **a status** (PASS / WARN / FAIL / NOT MEASURED),
**a score 0–100**, and **the facts behind it**.

| Dimension | Score formula (initial) | FAIL when |
|---|---|---|
| Functional correctness | passed / (passed + failed) tests, excluding quarantined | any non-quarantined failure |
| Stability | 100 − 100 × flaky / executed (flaky = passed only after a retry, or failed then passed within the run) | flaky rate above `forge.quality.max-flaky-rate` |
| Contract conformance | 100 − 10 per new mismatch, floor 0 | a new BREAKING contract change or mismatch |
| Data compatibility | 100 for SAFE only; −20 per RISKY, −50 per BREAKING | any BREAKING schema change |
| Robustness | 100 − 10 per new fuzz finding group | new finding groups above `max-new-fuzz-findings` |
| Authorization | 100 − 20 × access-matrix violations | any violation |
| Efficiency | 100 × (compared − slower) / compared, a test slower when ≥ 2× **and** ≥ 1 s slower than in the baseline (query budgets and HTTP latency publish no files to read) | never fails; WARN only |
| Security | from Part B: 100 − weighted new findings (critical 40, high 20, medium 5, low 1) | any new critical or high finding |
| Test hygiene | 100 − 5 × leftover rows − 10 × run-index reporting problems (skipped tests listed, not scored) | never fails; WARN only |

**Overall assessment**: the worst status across measured dimensions, plus
the unweighted mean score for trend lines. No single magic number decides a
release; the gate looks at statuses.

### 3.3 Baseline and trend

- `quality-baseline.json` — the last accepted run's dimension facts. Stored
  as a CI artifact or a checked-in project input, like the other baselines;
  never rewritten by the check itself.
- Each report shows per dimension: now, baseline, delta, and the facts that
  changed (e.g. "2 new contract mismatches: `GET /orders/{id}` field
  `status`…").
- Promoting a new baseline is an explicit `qualityRunner.writeBaseline()` in
  a reviewed change.

### 3.4 Outputs

- `quality.md` — the page a person reads first: overall status, a table of
  dimensions, what got worse, links to the evidence.
- `quality.json` — the same, machine-readable, for dashboards.
- Both registered in the run index under `module-quality`.

### 3.5 Gate and API

```java
QualityReport report = qualityRunner.run();          // never throws on a bad product
QualityReport gated  = qualityRunner.assertAcceptable(); // fails on FAIL statuses
```

```yaml
forge:
  quality:
    enabled: true
    baseline-file: build/quality/baseline/quality-baseline.json
    max-flaky-rate: 0.02
    max-new-fuzz-findings: 0
    fail-on: [functional, contracts, data, security]   # dimensions that may fail the build
    accepted:                                          # regressions the team accepted, with a reason
      - "efficiency GET /reports  # REP-112, known slow until the index lands"
```

Run as the last step of a CI pipeline, after the test jobs, reading their
artifacts.

## 4. Part B — security block (`module-security`)

Four kinds of checks, from harmless to intrusive, plus a self-check of the
run. Each is a separate, individually enabled part.

### 4.1 Passive checks on every response (B1)

Inspect responses the tests already receive through `ApiClient` — no extra
traffic. Runs in any environment, including shared ones.

- Security headers: `Strict-Transport-Security` on HTTPS,
  `X-Content-Type-Options: nosniff`, `Content-Security-Policy` /
  `X-Frame-Options` on HTML, `Cache-Control: no-store` on authenticated
  responses.
- Cookies: `Secure`, `HttpOnly`, `SameSite` on session cookies.
- CORS: `Access-Control-Allow-Origin: *` together with credentials; origin
  reflection.
- Information disclosure: server/framework version headers, stack traces and
  SQL errors in error bodies, internal hostnames/IPs.
- Sensitive data exposure: patterns for tokens (JWT, API keys), card numbers
  (Luhn), e-mails/phones in responses the spec marks as not containing them —
  reported by field path, never by value.
- Error hygiene: a 500 for a malformed request (should be 4xx).

### 4.2 Authentication and authorization tests (B2)

Built on `module-auth` roles and the existing access matrix.

- **Unauthenticated access**: every operation the spec marks as secured is
  called without credentials; anything but 401/403 is a finding.
- **Token handling** (with `local-jwt`): expired token, wrong signature,
  `alg: none`, a token for another audience, a tampered role claim — each
  must be rejected.
- **Object-level authorization (BOLA/IDOR)**: a resource created as role A
  (through a write chain the project configures) is read, changed and deleted
  as role B and as another tenant; any success is a finding. Write steps need
  the same two keys as `module-api-explorer` write chains.
- **Function-level authorization**: the access matrix extended to write
  operations, behind the same two keys.
- **Session**: logout invalidates the token/cookie; a session cookie changes
  after login (fixation).

### 4.3 Active scanning, delegated (B3)

- OWASP ZAP (API scan with the OpenAPI document from
  `forge.api-discovery.specs`, or baseline scan for a web UI), run as a
  container or a local install, like Schemathesis for `module-api-fuzz`.
- TestForge generates the ZAP configuration: target allowlist, scan policy
  (no DoS-class rules, no destructive rules), authentication through
  `module-auth` tokens, request rate limit, a time budget.
- Injection payloads for API inputs are also available through Schemathesis'
  checks; the spec does not add a third fuzzer.
- Runs only from an explicit security profile job against a test
  environment; refuses to start when the target is not in
  `forge.security.active.allowed-hosts`.

### 4.4 Transport (B4)

- TLS: certificate validity and expiry window, hostname match, protocol
  versions (no TLS < 1.2), weak cipher suites, HTTP → HTTPS redirect.
- Plain JDK checks, no external tool.

### 4.5 Self-checks of the test run (B5)

The test framework must not become the leak:

- Scan the run's own artifacts (`build/testforge-artifacts`, HTTP logs,
  diagnostics) for secrets that slipped past redaction — tokens, passwords
  from `forge.auth.roles.*`, private keys. A hit fails the run before the
  artifacts are uploaded.

### 4.6 Findings model

One record for every check, so all of them share the gate and the report:

| Field | Meaning |
|---|---|
| `id` | stable key: `<check> <target>`, e.g. `cors-credentials-wildcard GET /orders` |
| `check` | which rule (B1–B5 and its name) |
| `severity` | critical / high / medium / low / info, fixed per check, documented |
| `target` | operation, URL path, header or artifact path — never a value |
| `evidence` | what was observed, redacted; link to the request in the run index |
| `owasp` | OWASP API Security Top 10 / ASVS reference where one applies |
| `status` | new / accepted (with reason) / resolved since baseline |

Outputs: `security.md` (by severity, new first), `security-findings.json`,
SARIF (`security.sarif`) so findings show in code-scanning UIs. All three in
the run index under `module-security`.

### 4.7 Security assessment

- Per-check coverage: what was checked, against which operations, with which
  roles — "no findings" is only reported together with what was covered.
- Severity counts, new vs accepted vs resolved.
- The Security dimension of Part A (3.2) is computed from these.

### 4.8 Configuration sketch

```yaml
forge:
  security:
    passive:
      enabled: true                 # B1, safe everywhere
    auth:
      enabled: true                 # B2 read-only parts
      write-checks: false           # BOLA/IDOR writes: needs allow-unsafe-methods too
      allow-unsafe-methods: false
    active:
      enabled: false                # B3, security profile only
      allowed-hosts: [staging.example.test]
      max-requests-per-second: 10
      time-budget: 20m
    transport:
      enabled: true                 # B4
    artifacts-scan:
      enabled: true                 # B5
    fail-on: [critical, high]
    accepted-findings:
      - "missing-csp GET /api/health  # SEC-41, JSON only, no HTML"
```

## 5. Out of scope

- Static analysis of the product's source and dependency CVE scanning:
  these belong to the product's own CI (SAST, SCA, secret scanning in the
  product repository); the quality report may link their results later.
- Load and stress testing, and anything resembling denial of service.
- Penetration testing by people; this block automates repeatable checks, it
  does not replace an assessment.
- Mobile binary analysis.

## 6. Delivery steps

Each step is one PR with an example in `example-tests`, README, AGENTS.md,
CHANGELOG, and a review step at the end of each part.

| Step | Deliverable | Acceptance |
|---|---|---|
| A1 | `module-quality`: read manifest + JUnit XML, dimensions functional, stability, test hygiene; `quality.md` / `quality.json` — **shipped** | an example run produces a report whose numbers match the run by hand |
| A2 | Baseline, deltas, `assertAcceptable()`, `accepted` list — **shipped** | a regression fails, an accepted one does not, a new baseline only on purpose |
| A3 | Dimensions from existing modules: contracts, data, robustness, authorization, efficiency — **shipped** | each dimension NOT MEASURED when its module is absent |
| B1 | `module-security` passive checks on `ApiClient` responses + findings model + `security.md` / JSON / SARIF | WireMock example with a bad header, a wildcard CORS and a leaked token; all found, no value printed |
| B2 | Auth tests: unauthenticated, token tampering, BOLA/IDOR read, function-level | example with a deliberately broken endpoint per check |
| B3 | ZAP delegation with safety policy and allowlist | refuses a host not in the allowlist; containers test against a sample app |
| B4 + B5 | TLS checks; artifact secret scan | a planted secret in an artifact fails the run |
| A4 | Security dimension wired into Part A; review of both parts | one `quality.md` shows all dimensions including security |

## 7. Open questions

1. **Scoring weights** — are the initial formulas in 3.2 acceptable, or
   should a project be able to override them per dimension?
2. **ZAP** — run as a Docker container (needs Docker in the security job) or
   require a local install, as Schemathesis does?
3. ~~**Order**~~ — decided: Part A first.
4. **Severity of B1 checks** — fixed in the module, or overridable per
   project?
