# Security block: implementation decisions and integration contract

Part B is developed independently of module-quality. The originating
quality-security-spec draft remains owned by its author and is not edited here.

## Delivery and integration

B1 introduces the module, passive checks, findings/coverage, gate and reports.
B2 adds explicit authentication/access probes. B3 delegates active scanning;
B4/B5 add transport checks and a pre-upload artifact scan. Each stage gets its
own reviewable change. A4 (the quality dimension) belongs to Part A.

`security-findings.json` formatVersion 1 is the integration boundary. Read
`findings` (severity/status/id), `coverage`, `measured`, `healthy`, and
`newCounts`. Severity and finding status are lowercase strings. No Java
module dependency between quality and security is needed. No Security score
may be inferred from disabled, missing, failed or incomplete measurement.

## Interpretation

- A response-only check cannot know that a request was deliberately malformed.
  Error-hygiene checks require a project-declared negative scenario.
- Reflected Origin alone is not a vulnerability. Only a project-declared
  untrusted Origin being permitted is actionable. Wildcard plus credentials
  is a separate configuration finding, not proof of browser data access.
- CSP/frame policies apply to HTML, HSTS to HTTPS. Session cookie names and
  PII-forbidden response fields are project policy. Intentional token endpoints
  can be excluded from response credential checks.
- BOLA requires explicit owner/other-role/tenant fixtures and configured
  creation steps. Missing fixtures mean not measured; never invent ownership.
- Redirect-to-login, deliberate 404 concealment, 429 and 5xx require distinct
  outcomes; they must not all be treated as successful authorization tests.
- A scanner's process exit code, unavailable CLI, timeout or truncated output
  is execution failure/incomplete coverage, never a clean security result.
- Scanner rules need an allowlist and target scope enforced across redirects
  and embedded OpenAPI servers. Merely filtering alert output is not a scan
  safety boundary. Authentication values must not appear in process arguments,
  generated artifacts or reports. No DoS/destructive rule may be enabled.

## Reporting and limits

Only structural locations and fixed descriptions are retained. Raw bodies,
matched tokens/cards/cookies, scanner evidence strings and secret hashes are
not evidence fields. Truncated body scans and collector limits explicitly
mark incomplete coverage. Gates run explicitly before artifact upload.

Baselines are promoted explicitly; accepted findings require a reason. A
finding is resolved only when its check/target was measured again completely.
Absent coverage never resolves a finding or earns a perfect score.

## Primary references

- [OWASP API Security Top 10](https://owasp.org/API-Security/editions/2023/en/0x11-t10/)
- [ZAP API scan](https://www.zaproxy.org/docs/docker/api-scan/) imports OpenAPI
  then actively scans its URLs; the importer itself therefore needs scoping.
- [ZAP activeScan automation](https://www.zaproxy.org/docs/desktop/addons/automation-framework/job-ascan/)
- [ZAP network rate limiting](https://www.zaproxy.org/docs/desktop/addons/network/options/ratelimit/)
