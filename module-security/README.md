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
after the configured logout step. No cookie/token value enters findings.

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
      probe-legacy: true
      probe-weak-ciphers: true
```

Call `SecurityTransportRunner.check("https://api.staging.example.test")` and
then the shared report/gate. JDK TLS validates the chain, certificate validity
and hostname; expiry within the warning window is a medium finding. HTTP targets
receive one HEAD to inspect a same-host HTTPS redirect, without following it.
Targets require the transport allowlist and must not contain query credentials.

Legacy protocols/weak ciphers are probed only when the local JDK enables them.
Java 21 typically disables them; those checks then say not_measured, not
"server rejected them". This incomplete coverage cannot pass assertAcceptable.
A project may explicitly disable those probes; coverage then says no server-wide
claim was requested. No trust-all TLS context or global algorithm weakening is
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
