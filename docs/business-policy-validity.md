# Business policy validity and contract drift

The optional local review guard checks whether a business-security policy still
matches its reviewed **structural** OpenAPI contract before it sends any request.
It does not infer permissions, fetch credentials, approve policy changes or earn
API-security coverage.

## Review lifecycle

```java
// Pure metadata: input names, never their values. Roles are configured aliases.
var report = policies.assess(document, suite, Set.of("order"));
// After project review, explicitly create/replace the structural review:
policies.writeReview(document, suite, Set.of("order"));
// With policy.required=true, BusinessSecurityRunner runs the guard automatically.
business.run(document, suite, Map.of("order", runtimeOrderId));
security.assertAcceptable();
```

`assess` reads existing review metadata and writes a JSON/Markdown assessment. It
never updates the review. `inspect(document, suite, review, inputNames)` compares
an explicitly supplied review without file IO. Only `writeReview` promotes a new
snapshot. It refuses unresolved/invalid preflight and leaves previous review
bytes intact on failure. Replacement is atomic where the filesystem supports it.

```yaml
forge:
  security:
    business:
      enabled: true
      test-environment: true
      allowed-hosts: [api.staging.example.test]
      # Existing two write switches are still needed for write cases.
      policy:
        required: true
        review-dir: src/test/resources/security/policy-reviews
        max-operations: 500
        max-schema-nodes: 20000
        max-depth: 32
        max-review-bytes: 8388608
```

Defaults: `required=false`, review directory `build/security/policy-reviews`.
For persistent approvals configure a project-controlled directory and review the
files as source changes. Checks do not silently rewrite it. Missing review for a
new suite/version requires explicit review. Treat review artifacts as trusted
project inputs, not cryptographic signatures or proof that a person approved a
change.

## Snapshot and impact

Review format 1 records suite/version, operation inventory, case dependencies,
structural case shape and scope, and contracts of the **used operations**. It
includes path/query/header/cookie declarations, required flags, JSON shapes,
local schema references, composition, nullability, readOnly/writeOnly, selected
formats, response media/status declarations, security requirements/scheme
placement, and normalized local Link metadata.

Local schema cycles are bounded and preserve reference structure. Referenced
components are included transitively; unused definitions are omitted. The
snapshotter performs no reference fetch. Resolution performed earlier by the
existing discovery parser is separate from this no-fetch contract. The same
structural-path and local-Link bounds as scenario generation apply.

A used operation/schema/Link change marks dependent cases for review. Changing
an excluded operation's schema or an unused component does not invalidate those
cases. Added/removed operations are suite-level inventory changes: coverage and
exclusions must be revisited. New cells cannot be silently excluded. Case results
and suite-wide issues distinguish affected cases from scope problems. The live
guard validates the entire suite before its first control or setup request.

## Preflight

The guard checks, without HTTP or token acquisition:

- Configured role aliases, case/operation scope and existing owner/tenant/replay
  controls, persisted-state verification, cleanup shape and work budgets.
- Missing path/query bindings, required bodies, unsupported read bodies/content
  types and method-override query parameters.
- Binding availability in execution order. Assertions run before captures of the
  same step; an entire value may be a binding, partial interpolation is invalid.
- Cleanup inputs can only come from supplied input aliases or captures of
  pre-probe controls. Cleanup cannot rely on probe/verification responses or an
  earlier cleanup succeeding.
- Capture and positive-assertion JSON Pointers against declared JSON response
  shapes. Missing pointers in closed schemas are invalid; incomplete/open or
  unknown schemas require review. ABSENT assertions may name undocumented fields,
  but still need a declared JSON response. Bodyless responses cannot feed captures.
- Explicit coverage decisions for the whole document × role × selected-kind
  matrix. Conflicting/stale exclusions are invalid.

Role existence is checked using metadata only; token validity is established by
execution controls, not this check. Required arbitrary headers/cookies need
project placement validation and cannot be silently approved. Target allowlists,
write opt-ins, actual inputs and transport limits remain enforced by the existing
live executor before requests. Static VALID is not a claim that the environment
or its business behavior is secure.

## Status and gate

| Status | Meaning |
|---|---|
| VALID | Supported static checks pass and current structures match the explicit review |
| REVIEW_REQUIRED | Review missing/stale, scope changed, or schema information cannot establish validity |
| INVALID | Structurally inconsistent policy, unavailable binding, missing pointer/operation, corrupt review or unsupported/bounded metadata |

Assessments: `business-policy-<suite>-<version>.json` and `.md` in the security
output directory; review: `<suite>-<version>.review.json` in `review-dir`. Issues
identify case/step, fixed reason code and structural location where available.
Reports register as `business-policy` with `measured=false`, not as findings or
security measurements. Excess issue detail is capped and explicitly reported.

`policy.required=true` requires **both** the guard and actual business execution
in the shared security gate, even if another block measured successfully. A
stale/invalid assessment stops before HTTP and creates incomplete coverage; it
cannot be accepted away by finding severity thresholds or a security baseline.
A successful static guard earns no measured API checks. The existing quality
adapter consumes completeness from the shared security report.

A failed run stays failed: reviewing a fix does not remove its earlier incomplete
coverage. Start a fresh run/collector after correction, preserving CI evidence.

## Values and supported scope

Snapshots/report diagnostics never contain runtime IDs, request/response values,
credentials, source examples/defaults, literal Link payloads, or hashes of these
values. Expected literal values are represented by type only. Numeric constraint
values, regex text and enum contents are intentionally omitted (presence/count
is recorded); custom format names are classified rather than copied.

Consequently this is a structural validity guard. Changing a literal business
expectation or an omitted constraint value requires an explicit **new policy
version and project review**. The module does not automatically prove semantic
identity of such edits. Keep IDs, field names, pointers, role/tenant aliases and
exclusion reasons non-sensitive: they are structural metadata retained in files.
Unsupported names/references/depths/budgets fail explicitly rather than returning
an empty perfect snapshot. Execution safety, deployment approval and business
oracles remain the project's responsibilities.

## Regressions

`BusinessPolicyTest` exercises explicit promotion, dependency-level impact,
missing/future bindings, cleanup dependencies, role errors, source/expected-value
privacy, review-file failure and recursive schemas. The live local
`BusinessPolicyValidityExampleTest` checks review → successful run → contract
drift → zero new HTTP requests → updated policy and explicit review → a fresh
successful run. It covers both id → orderId and type drift in a field that the
old runtime assertions did not use. The published-JAR consumer exercises the
same guard through Spring auto-configuration.
