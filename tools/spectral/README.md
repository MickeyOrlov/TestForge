# OpenAPI security lint with Spectral

Project-level workflow over Stoplight's [Spectral](https://github.com/stoplightio/spectral)
(Apache-2.0) and its [OWASP API Security Top 10 ruleset](https://github.com/stoplightio/spectral-owasp-ruleset)
(MIT, 2023 edition), pinned to `@stoplight/spectral-cli` 6.17.0 and
`@stoplight/spectral-owasp-ruleset` 2.0.1 in package-lock. Spectral owns parsing, `$ref`
resolution and the rules. This workflow writes one SARIF 2.1.0 report per document;
module-quality's `static-analysis` dimension judges it. Not a Java module, and no
module depends on it.

It reads the API **description**, not the API: a missing security scheme, an
unbounded string or array, no 401/429/500 responses, HTTP basic auth, a key in a query
parameter. What the running service does is module-security's job (passive checks,
auth probes, business policies, ZAP).

Requires Node 22 or ≥ 24 and npm. Run from the repository root:

```bash
./gradlew :example-tests:spectralLint \
  -PspectralSpecs=src/main/resources/openapi.yaml,other-service/openapi.json
```

```yaml
# the quality gate, after the lint
forge:
  quality:
    sarif:
      reports: [build/spectral]
    fail-on: [static-analysis]
```

`spectralTools` installs the lockfile with `npm ci --ignore-scripts` and is skipped
when the lockfile is unchanged. Nothing is attached to the default build.

## What the workflow adds to native Spectral

- **No network.** A custom resolver resolves local `$ref` files only; an http(s)
  reference fails as `invalid-ref`, so a document cannot make the lint fetch a URL.
  Inputs are 1..50 local `.yaml`/`.yml`/`.json` files, never URLs or globs. The
  child environment carries no CI tokens or `NODE_OPTIONS`.
- **Incomplete is not clean.** A document whose lint reports `invalid-ref`,
  `unrecognized-format` or `parser`, or which yields no valid SARIF, gets **no**
  report and the run exits 2: the OWASP rules did not see the whole document, and a
  partial report must not reach the gate as a complete one. Earlier `*.sarif` files
  in the output directory are removed first, so a stale report is never judged as
  this run's.
- **One issue per rule and operation.** Spectral's SARIF has no fingerprints, so
  module-quality would key all of a rule's results in one file as one issue — a newly
  unprotected operation would hide behind an old one. The same lint's JSON output
  carries each result's document path (`paths./orders.get`); it becomes the result's
  `partialFingerprints["documentPath/v1"]`, which survives moved lines. The two
  outputs must agree result by result, or the document counts as not linted.
- **The versions that ran.** Installed packages other than the pinned ones are refused.
  Spectral 6.17.0 writes a stale `6.16.3` as its SARIF driver version; the report is
  corrected to the installed version and names the ruleset in `driver.properties`.
- **One ruleset fix.** Ruleset 2.0.1 ships `owasp:api5:2023-admin-security-unique`
  with an empty description, and Spectral's SARIF formatter then refuses the whole
  report. [ruleset.mjs](ruleset.mjs) gives such a rule its id as description; the rules
  themselves are used as published.

Exit codes: 0 every document linted (with or without findings — the gate decides),
2 a refused input or a document not linted completely. Locations are
repository-relative, so findings are CODEOWNERS-owned in the quality report.

## Adopting it

The ruleset is strict: the example APIs in this repository have 29–50 findings
each, mostly missing 401/429/500 responses, rate-limit headers and string/array
limits. Run the lint, read `quality.md`, then promote the current state with
`testforge-quality baseline` and keep `fail-on: [static-analysis]` for new findings
only. Rules the API deliberately does not follow belong in `forge.quality.accepted`
with a reason and `until`. Severity follows the rule's level: `error` is high,
`warn` medium.

## Verification

```bash
./gradlew :example-tests:spectralUnitTest   # offline contract checks
./gradlew :example-tests:spectralAcceptance # native lint of owned documents
./gradlew :module-quality:test --tests '*SarifDimensionTest'
```

Acceptance lints two example documents and checks fingerprinted, repository-relative
SARIF from the pinned version, then checks that a remote `$ref` and a non-OpenAPI file
both leave no report and exit 2. module-quality's test reads a real report
(`spectral-6.17.0-owasp-2.0.1.sarif`): 29 results become 23 issues, unchanged when
every line moves.
