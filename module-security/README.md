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

The JSON contract (formatVersion 1) has `enabled`, `measured`, `healthy`,
`findings`, `coverage`, and `newCounts` by lowercase severity. Findings have
`id`, `check`, `severity`, `target`, `evidence`, `owasp`, `status` and `reason`.
Statuses are new/accepted/resolved. Part A can read this report without a Java
module dependency. Missing or unmeasured evidence must not become a perfect
Security score. Each coverage row names a check, target, optional role, status
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
