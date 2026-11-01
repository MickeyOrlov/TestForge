# module-security

Opt-in security evidence from API responses, with no extra traffic in passive
mode. `ApiClient` applies the `PassiveSecurityFilter` automatically. Checks
retain only structural locations and fixed descriptions, never response values.

```yaml
forge:
  security:
    passive:
      enabled: true
      session-cookies: [SESSION, JSESSIONID]
      forbidden-fields: [$.contact.email, $.contact.phone] # project policy, not a generic PII ban
      allowed-sensitive-paths: [POST /oauth/token]        # intentional credential responses
      untrusted-origins: [https://untrusted.example.test] # reflection is a finding only for these
      max-body-chars: 262144
    output-dir: build/security
    baseline-file: build/security-baseline.json
    fail-on: [critical, high]
    accepted-findings:
      - "missing-nosniff GET /legacy # SEC-12 migration pending"
```

Call `securityRunner.run()` after the requests to write `security.md`,
`security-findings.json` and `security.sarif`. Reports register through
`ArtifactSink` when reporting is enabled. `assertAcceptable()` additionally
fails on new configured severities and refuses disabled, unmeasured or
incomplete assessments. `writeBaseline()` is explicit and is the only method
that updates the baseline. A finding already in the explicitly promoted baseline
is accepted; additional accepted finding IDs require a reason.

The JSON contract (formatVersion 1) has `enabled`, `measured`, `complete`, `healthy`,
`findings`, `coverage`, and `newCounts` by lowercase severity. Findings have
`id`, `check`, `severity`, `target`, `evidence`, `owasp`, `status` and `reason`.
Statuses are new/accepted/resolved. Part A can read this report without a Java
module dependency. Missing or unmeasured evidence must not become a perfect
Security score. `measured` means some evidence exists; `complete` requires all
enabled blocks to execute and no incomplete coverage. Each coverage row names a check, target, optional role, status
(measured/not_measured/not_applicable) and reason.

## Rules and fixed severities

- High: credential-like response patterns, Luhn-valid card patterns,
  project-forbidden JSON fields, configured untrusted CORS origins allowed.
  Forbidden fields apply to JSON responses only: an HTML page, text or file
  is `not_applicable`, while JSON that cannot be parsed whole (cut at
  `max-body-chars`) is `not_measured` and keeps the gate closed.
- Medium: missing HTTPS HSTS, missing HTML CSP/frame protection, insecure
  session-cookie attributes, wildcard CORS with credentials, error stack/SQL
  patterns. This CORS combination is a misconfiguration, not proof that a
  browser successfully disclosed data.
- Low: missing nosniff, missing no-store on authenticated responses, missing
  SameSite, software version headers, private IP patterns in error responses.

No CSP/frame finding is raised on JSON; no HSTS finding on HTTP. Cookies are
checked only when their names match session-cookies. Rules are heuristic evidence,
not proof of exploitability. Email/phone policy must be declared explicitly.
Ordinary HTTP 500 responses do not prove malformed-input handling defects.

## Limits and safety

No source/dependency scanning, penetration-test claim or implicit writes.
No response bodies, credential hashes or observed values enter the collector,
report, SARIF or gate errors. Request query strings, hosts and identifier-like
path segments are omitted from finding targets. For unusual sensitive path
segments, use non-sensitive API routes; this is not a general anonymizer.
Bodies are checked up to the configured limit; truncation and collector overflow
are explicit incomplete coverage and cannot pass assertAcceptable.

Passive reporting is explicit (`run`/`assertAcceptable`); call the gate before
uploading CI artifacts. Module removal leaves the rest of the template usable.
Example: `SecurityPassiveExampleTest`.

## B2: explicit authentication and access probes

```yaml
forge:
  security:
    auth:
      enabled: true
      allowed-hosts: [127.0.0.1, api.staging.example.test:8443]
      max-requests: 1000
      denied-statuses: [401, 403] # add 404 or a login redirect only when policy explicitly uses them
      write-checks: false
      allow-unsafe-methods: false
```

`SecurityAuthRunner` exposes `unauthenticated(PreparedRequest)`,
`securedOperations(specId, OpenAPI, ApiExplorerProperties)`,
`jwtRejections(request, localJwtRole, requiredAudience)`,
`objectAccess(request, ownerRole, otherRole)`, `session(role, preLogin, logout,
protectedResource)`, `malformed(request)` and `accessMatrix(existingReport)`.
The last method consumes the existing matrix result without sending more traffic.
OpenAPI parsing/operation selection/request planning reuse the existing modules.

The project supplies owned fixtures/tenant roles through its write chains.
BOLA/JWT/session checks require a successful positive control before interpreting
negative responses. Missing controls, redirects not declared as denial, 429,
5xx and transport failures are incomplete coverage, never successful protection.
JWT cases reuse module-auth's signer: expired, wrong signature, alg:none,
unsigned role tampering, and wrong audience when an audience is declared.
Session checks compare pre-login/post-login cookies and retry the saved cookie
after the configured logout step, then drop the role's cached session, so the
role's next request logs in again instead of using the logged-out cookie. No
cookie/token value enters findings.

Probes use ApiClient's base URL/timeouts and module-auth's filter/credential
placement. They explicitly clear default headers/cookies/query credentials,
custom filters/retries and automatic redirects so anonymous and invalid-token
checks cannot accidentally inherit a valid credential. Project-specific signing
filters are therefore not applied to probes; missing project prerequisites
must be supplied explicitly in PreparedRequest data, not inferred. Use only
configured, disposable test fixtures. Every non-GET/HEAD/OPTIONS request needs
both write keys. An exact host allowlist is required before any probe request.
Example: SecurityAuthExampleTest. Auth probe collection is explicit; call
SecurityRunner afterward to write/gate the shared report.

## B4: explicit transport checks

```yaml
forge:
  security:
    transport:
      enabled: true
      allowed-hosts: [api.staging.example.test]
      expiry-warning-days: 30
      timeout: 5s
      probe-legacy: false        # default; true only on a JDK that still offers TLS 1.0/1.1
      probe-weak-ciphers: false  # default; likewise for weak cipher suites
```

Call `SecurityTransportRunner.check("https://api.staging.example.test")` and
then the shared report/gate. JDK TLS validates the chain, certificate validity
and hostname; expiry within the warning window is a medium finding. HTTP targets
receive one HEAD to inspect a same-host HTTPS redirect, without following it.
Targets require the transport allowlist and must not contain query credentials.

Legacy protocols/weak ciphers are probed only when asked for and only when the
local JDK enables them. Java 21 typically disables them; enabled probes then
say not_measured, not "server rejected them", and that incomplete coverage
cannot pass assertAcceptable — so both are off by default, and coverage says no
server-wide claim was requested. Turn them on in a job whose JDK offers them. No trust-all TLS context or global algorithm weakening is
installed. Tests generate a temporary certificate/keystore at runtime.

## B5: pre-upload artifact secret check

```yaml
forge:
  security:
    artifacts-scan:
      enabled: true
      roots: [build/testforge-artifacts, build/security, build/http-logs]
      max-file-bytes: 8388608
      max-total-bytes: 67108864
      max-files: 10000
```

Run `securityArtifactScanner.assertClean()` after reports/logs are flushed and
before uploading anything. Then write/gate the shared SecurityRunner report.
Static configured role tokens/client secrets/passwords/JWT signing secrets and
already-used request credentials are kept only in a bounded memory registry.
Collection never obtains a new token or sends traffic. Projects can register
other known secrets with `SecuritySecretRegistry.register(value)`; its toString
is masked. JWT/private-key/credential-assignment patterns supplement exact matching.

Archives are opened: every entry of a `.zip`/`.jar`/`.war` (a Playwright
trace holds request headers) and the stream of a `.gz`/`.tgz` is scanned, the
decompressed bytes counting against the same budgets, so a zip bomb stops
early. A nested archive is not_measured; images and videos are not_applicable.

No file content, match, secret hash or raw error enters a finding. Scans do not
follow encountered symbolic links; missing/unreadable/oversized files and file,
byte or registry limits mark incomplete coverage. File IDs retain safe names
and mask identifier/secret-like segments. The scanner examines configured roots,
not the whole workstation; include every artifact/log destination in roots.
Artifact leaks always block assertClean and SecurityRunner.assertAcceptable,
even with an accepted ID or an empty fail-on list. Do not upload on a gate error.
Examples: SecurityTransportExampleTest and SecurityArtifactExampleTest.

## B3: scoped OWASP ZAP delegation (Docker)

The scanner is a separate opt-in CI job. The image must already exist locally;
it is pinned by digest and is never downloaded implicitly.

```yaml
forge:
  security:
    active:
      enabled: true
      test-environment: true
      allowed-hosts: [api.staging.example.test:8443]
      role: security-reader                # optional configured module-auth role
      mode: api                            # or baseline for configured pages only
      rules: [40003, 40012]                 # reviewed CRLF / reflected-XSS only
      max-requests-per-second: 10           # hard maximum 20
      max-requests: 1000                    # hard maximum 10000
      max-response-bytes: 2097152
      time-budget: 20m                     # maximum 1h; startup/cleanup bounded separately
```

Pass a parsed OpenAPI 3.0 document from the existing discovery parser to
`SecurityActiveRunner.scan(document, targetBaseUrl)`. Only its GET/HEAD/OPTIONS
operations are imported. Document-supplied servers/authentication callbacks,
remote references, examples and defaults cannot select scanner targets.
Active payloads are limited to query parameters; request-body, path, header,
cookie and script input vectors are disabled in this initial policy. Input
documents/plans are limited to 8 MiB. No synthesized product source or
third fuzzer is introduced.

ZAP talks only to a random-path loopback reverse gateway. The gateway fixes the
upstream origin, checks declared paths/methods, strips inherited credentials and
method-override headers/parameters, injects the configured role in memory,
enforces request/rate/body/time limits and refuses cross-origin redirects.
HTTPS upstream uses normal JDK certificate validation. No credential goes into
Docker arguments, generated plans, public reports or scanner configuration.
The initial active policy disables all other active rules; DoS, time-based,
SQL/DML, persistent-XSS and other unreviewed rules cannot be enabled by config. HTTP method scope does not prove
business harmlessness: the project must select disposable routes whose GET
operations have no business side effects.
This is deliberately narrower than a full ZAP API scan or a pentest.

`mode: baseline` omits active scanning. `baselinePages(paths, target)` checks
only the project's explicit read-only pages; there is no web crawling.
Both modes collect ZAP's passive findings. The framework does not run against
production or infer permission from an OpenAPI servers entry: enabled,
test-environment and an exact host allowlist are required before startup.

The Docker container has a read-only root filesystem, temporary writable
storage, no added capabilities, bounded CPU/PIDs/RAM and a 512 MiB JVM heap.
No Docker ports are published. Docker Desktop reaches host loopback through
host.docker.internal; Linux uses host networking with a loopback-only listener.
This transport setup is not a claim of a general network sandbox for an
arbitrary/untrusted container image: use only the reviewed pinned scanner.

Raw scanner responses/console content never become run artifacts. A bounded
raw JSON report is consumed through a private temporary file and removed;
the container is removed even on error. The shared JSON/Markdown/SARIF report
retains only rule IDs, severity, structural route and fixed descriptions.
Failures expose phase/exit code, not raw scanner output. Failed startup,
timeouts, invalid evidence, cleanup failure, blocked unsafe methods, in-scope failures/budgets and missing
successful route controls remain incomplete coverage and cannot pass the gate.

Out-of-scope read requests are still refused and listed as not_applicable
scope exclusions; they are not silently dropped and do not expand the target.

ZAP coverage means the configured policy completed on successfully visited
routes, not that every possible attack/payload was exercised. Baseline resolution
requires the same pinned scanner, policy, mode and role plus complete route
coverage; their non-secret configuration is identified by a policy fingerprint.
Changing to a weaker policy cannot resolve earlier findings.

Real container acceptance (owned local sample only):

```bash
./gradlew :example-tests:securityScanTest
```

This separate Jupiter task avoids unrelated empty tagged Cucumber suites in the
general containers task. It stays out of the default offline build. See
SecurityZapIT; focused fake-engine tests cover policy refusal and report privacy.

Artifact scanning matches raw text/known credential patterns; it is not OCR,
archive decryption or a general DLP classifier for screenshots/documents.

The quality adapter reads `complete` and coverage alongside findings. Selecting
security in the quality gate rejects incomplete evidence. Current artifact
secret leaks always stay `new`, make `healthy` false, and cannot be promoted into
a security baseline, regardless of accepted findings or severity thresholds.

## Business security: declared policies over OpenAPI

`BusinessSecurityPlanner.propose(document, suite, maxCells)` produces **candidates**
without traffic: operation × role × selected check kind, top-level readOnly /
writeOnly field hints, and possible control → probe → verification sequences.
Matching a GET on the same entity path or its parent is a scaffold for review,
not proof of a business relationship. Permissions, tenant ownership, expected
statuses, protected fields and business states always come from project policy.
An incompatible read operation/write-check candidate has no suggested sequence;
exclude it explicitly instead of counting it as measured. Document servers,
examples, defaults and links never choose targets, credentials or test values.

`BusinessSecurityRunner.run(document, suite, inputs)` executes the reviewed
`BusinessSecuritySuite` records (also deserializable from JSON). Six case kinds:

| Kind | Project-declared assertion |
|---|---|
| PROPERTY_READ | Forbidden response fields must be absent for the actor |
| PROPERTY_WRITE | Attempt to change protected fields, then read persisted state |
| OBJECT_ACCESS | Owner control succeeds; another role cannot access the object |
| TENANT_ACCESS | Distinct owner/actor roles with explicitly different tenant aliases |
| TRANSITION | A prohibited state change is refused and persisted invariants hold |
| REPLAY | Identical request succeeds once; repeat obeys policy and effects stay bounded |

```yaml
forge:
  security:
    business:
      enabled: true
      test-environment: true
      allowed-hosts: [api.staging.example.test:8443]
      write-checks: true             # both keys required for every write, including cleanup
      allow-unsafe-methods: true
      max-requests: 1000             # per run, including controls and cleanup
      max-cases: 100
      max-steps: 50                  # per case, including cleanup
      max-cells: 10000
      max-body-bytes: 1048576        # request/response/expanded value and case bindings
      timeout: 5m
```

The target is `forge.http.base-url`; connection/read timeouts reuse module-http.
Roles/credential placement reuse module-auth, including explicit `anonymous`.
Read requests cannot carry bodies; method-override query parameters are rejected.
Default headers, credentials, cookies, filters, diagnostics and retries are not
inherited. This private JDK transport bounds the response **during receipt**,
uses normal TLS verification, and never follows redirects. It intentionally
supports JSON bodies and scalar path/query bindings, without arbitrary request
headers; project signing/customizer prerequisites are not inferred. Credential
acquisition remains governed by module-auth's own configuration/timeouts; the
suite deadline prevents subsequent API requests after its budget is exhausted.
Runtime credentials also enter the existing in-memory secret registry.

Every case has a successful CONTROL before its matching PROBE. Write cases
need an asserted VERIFY after the last write. Object access needs a distinct
owner GET control on the same entity path (or its action's parent), using the
same declared path/query bindings as the probe. Persisted-state GET verification
must likewise refer to the last mutated fixture; tenant checks additionally need the suite's explicit `tenants`
map. Aliases describe disposable fixtures supplied by the project; the framework
does not infer tenant membership from role names or decode tokens to prove it.
REPLAY requires a preceding identical successful request under the same role.

Steps declare expected statuses, JSON Pointer assertions (ABSENT, PRESENT,
EQUALS), and captures `{bindingName: "/json/pointer"}`. Empty pointer means the
whole JSON value. `${binding}` as an **entire JSON string node** substitutes the
captured JSON value with its original type; a path/query value must be scalar.
There is no partial-string interpolation. Captures cannot overwrite inputs or
previous captures. Values stay case-local, are bounded, never reach reports,
and are discarded after cleanup. Request templates, expected values and inputs
are not report artifacts; keep sensitive policy data out of source control.

A failed positive control, missing binding, malformed/oversized response,
unconfigured status, redirect, 429, 5xx, timeout or cleanup failure leaves the
case incomplete. An unexpected 2xx probe or a failed probe/verification invariant
produces a HIGH finding. A denial status alone cannot establish protection for
writes: verification can still find a persisted mutation. Cleanup controls are
attempted in `finally`, including after failed controls; they obey the same
allowlist, write switches and remaining budgets. Provision disposable fixtures
with an independent expiry/cleanup mechanism for crashes or exhausted budgets.
Use module-state/module-flow for reusable preparation, then pass fixture IDs
as inputs; this runner has no branching, retries or workflow state search.

Coverage is the **whole supplied document × declared roles × selected kinds**.
Each cell needs completed cases or an explicit exclusion with a non-sensitive
policy/ticket alias as its reason. Undeclared cells are `not_measured`, not
implicitly excluded. Multiple cases in a cell must all finish. Incidental
control/cleanup calls do not earn coverage for other cells. Exclusions cannot
conflict with cases. This measures the declared cases, not every possible
business attack or the completeness of the OpenAPI document itself.

The runner writes `business-<suite>-<version>-coverage.json` and `.md`, registered through
ArtifactSink. Call the existing `SecurityRunner.assertAcceptable()` afterward
for shared JSON/Markdown/SARIF findings and the gate. An enabled but unexecuted
business block or incomplete matrix keeps that gate closed; the quality adapter
already consumes this completeness contract. A wholly excluded matrix earns no
measurements and cannot alone pass the shared gate.

Suite/case/step/role/version identifiers and exclusion reasons are policy aliases
retained in artifacts: never put credentials or business identifiers in them.
Finding IDs include suite, policy version, case, structural operation and role.
**Change the version when expectations or scenario semantics change.** Old cases
are not automatically resolved by a different suite/version or an exclusion;
use an explicitly reviewed baseline migration for policy changes. Read-only
observations of absence are not evidence that an untested policy was repaired.

Living examples: `BusinessSecurityExampleTest` and
`example-tests/src/test/resources/security/orders-business-policy.json`:
owner/other user in tenant A, another user in tenant B, protected-field attempts,
state re-read and cleanup. `BusinessSecurityTest` additionally exercises state
transitions, replay and deliberately vulnerable API behavior. No remote service
or ZAP container is needed for these examples.

## Linked scenario drafts

`BusinessScenarioGenerator.generate(document, seeds)` now builds bounded local
OpenAPI Link graphs and editable policy templates, including captured response
IDs, shared path/query bindings, owner controls, replay checkpoints, verification
and explicit cleanup choices. Path-derived edges remain reviewable hints.
Unresolved or ambiguous chains are blocked; examples/defaults and literal Link
values are never copied. Generation has no traffic and earns no measurements.

Draft status sets and state assertions are empty: project review is required
before the existing business executor can run them. See
[the generation guide](../docs/business-scenario-generation.md),
`BusinessScenarioGenerationExampleTest` and its linked OpenAPI fixture for the
complete OpenAPI → draft → reviewed policy → local execution flow.

Setup controls may create a resource at a collection URL, capture its ID and
read the resulting entity. Cases with write setup still need final state
assertions for the primary fixture after the probe. State checks for write
probes and any subsequent mutation must address the same bound fixture.
