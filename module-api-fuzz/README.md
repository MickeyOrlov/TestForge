# module-api-fuzz

Give it an OpenAPI document and an environment; get back a rigorous, schema-aware API fuzzer report.

This module is a **thin adapter** that runs the external [Schemathesis](https://schemathesis.readthedocs.io/) CLI. Schemathesis owns generation, coverage, negative testing, shrinking, stateful testing, replay, and reporting. TestForge owns configuration, the safety policy, command construction, process execution, report ingestion, and run evidence. **TestForge contains no fuzz engine.**

## What's inside

- **`ApiFuzzRunner`** — orchestrates the fuzzing process. It runs Schemathesis for every configured spec, writes the report, and provides `assertHealthy()`. That method always fails on an execution or configuration error; it fails on API *findings* only when `forge.api-fuzz.fail-on-findings=true` (default `false`), for the same reason `module-api-explorer` defaults `fail-on.contract-mismatch` to false — a red build on the first run against an unfamiliar API teaches a team to switch the module off rather than read the report.
- **`FuzzSafetyPolicy`** — decides what HTTP methods are permitted. `GET`, `HEAD`, and `OPTIONS` need no opt-in; any mutating method needs two mechanisms enabled.
- **`FuzzSpecMaterializer`** — copies the target OpenAPI document to a temporary file, ensuring Schemathesis runs against the exact specification version TestForge discovered.
- **`SchemathesisCommand`** / **`SchemathesisConfigFile`** — constructs the safe `st run` arguments and the `schemathesis.toml` configuration required to constrain the coverage phase.
- **`SchemathesisExecutor`** / **`ProcessRunner`** — the execution seam that runs the CLI process and captures its output.
- **`NdjsonReportParser`** — consumes the NDJSON event stream to classify the outcome since exit codes alone are ambiguous.
- **`FuzzEvidenceWriter`** / **`FuzzRunEvidence`** — writes and structures run evidence, persisting the exact CLI version, seed, URL (redacted via `UrlRedactor`), and arguments so the run can be reproduced.

## Installation

Schemathesis is **not** a Gradle/build dependency and is never installed by `./gradlew build`. It must be installed separately. The tested version is 4.24.3.

For local use or in a reproducible CI script, install it via `uv`:

```bash
uv tool install schemathesis==4.24.3
# or run directly: uvx schemathesis==4.24.3
```

Note: The executable installed is `st`.

## Configuration

Specs are not configured here; the fuzz module reads the registry `module-api-discovery` already owns, exactly as `module-api-explorer` and `module-api-codegen` do. One document, one place to point at it.

```yaml
forge:
  api-discovery:
    specs:
      demo:
        location: classpath:/openapi/demo-api.yaml
  http:
    base-url: https://api.staging.example.test
  api-fuzz:
    enabled: false                # default; nothing exists until this is true
    output-dir: build/api-fuzz    # default
    specs: []                     # empty = every configured spec
    base-url:                     # optional override for forge.http.base-url
    methods: [GET, HEAD, OPTIONS] # default
    allow-unsafe-methods: false   # default; required for anything else
    phases: [coverage, fuzzing]   # default; can also include examples, stateful
    seed:                         # optional fixed seed for reproducibility
    max-examples: 50              # default cases per operation
    generation-mode: all          # default (positive, negative, all)
    max-failures:                 # optional limit to fail early
    timeout-seconds: 900          # default
    command: st                   # default executable
    config-file:                  # optional user-supplied schemathesis.toml
    fail-on-findings: false       # default; execution errors always fail
    accepted-findings: []         # known findings the gate ignores, see below
```

## Safety

The safety policy enforces a strict two-key rule: `GET`, `HEAD`, and `OPTIONS` need no opt-in, but a mutating method needs **both** the method listed in `methods` **and** `allow-unsafe-methods=true`.

Enforcement needs **two** mechanisms, not one. Schemathesis's coverage phase otherwise emits requests with methods that are not in the spec (a GET-only configuration was observed emitting TRACE). TestForge passes `--include-method` to the CLI **and** generates a `schemathesis.toml` with `unexpected-methods = []` under `[phases.coverage]`.

Read [docs/api-fuzz-prototype-analysis.md](../docs/api-fuzz-prototype-analysis.md) for a deeper analysis of this behaviour.

## Secrets

**Never put tokens in arguments.**

`--report ndjson` records the full command line in its `Initialize` event, so `argv` ends up inside a TestForge artifact.

The supported approach is a user-supplied `schemathesis.toml` (configured via `forge.api-fuzz.config-file`) with environment substitution. Schemathesis resolves these values at runtime:

```toml
[auth]
bearer = "$STAGING_TOKEN"
```

## Reproducibility

Every run writes run evidence (run id, spec identity, credential-free target URL, Schemathesis version, seed, phases, methods, generation mode, exit status, report locations) so a failure can be perfectly reproduced. 

To reproduce a failure: rerun Schemathesis locally with the same `--seed`, and use the VCR cassette or `st replay` rather than any TestForge-specific replay machinery.

## Results

Every generated case that breaks a check is a finding, so one broken operation
easily produces dozens. They are grouped by check and operation —
`ApiFuzzReport.findingGroups()` — and each run writes
`<output-dir>/report.md`, which is the file to open first:

```text
## demo

- outcome: FINDINGS
- scenarios: 3 (2 failed)

### Findings

#### `not_a_server_error` on `GET /items/{id}` (3 cases)

- phases: Coverage, Fuzzing
- methods sent: GET, TRACE
- first message: Server error
- reproduce:

  st --config-file build/api-fuzz/demo/schemathesis.toml run /tmp/demo.yaml -u https://api.staging.example.test --include-method GET ... --seed 4242 ... --include-name 'GET /items/{id}'
```

The reproduce command is the run's own, with report flags dropped, the URL
redacted and `--include-name` narrowing it to the operation; add your own
`--config-file` with credentials as for any run. Only the first line of a
failure message is kept — tracebacks stay in the NDJSON — and request headers
and bodies are never written.

Exit code 1 covers both API findings and infrastructure failure. This is why the module classifies outcomes from the NDJSON report stream: a `NonFatalError` event means execution failure, not an API finding.

## Accepted findings

The first run against an unfamiliar API finds things that will not be fixed
this sprint. Rather than keep `fail-on-findings` off for everything, list what
was read and accepted, copying the heading `report.md` printed:

```yaml
forge:
  api-fuzz:
    fail-on-findings: true
    accepted-findings:
      - not_a_server_error GET /items/{id}      # ITEMS-412, fix planned
      - billing:unsupported_method GET /invoices # this spec only
```

An entry is `<check> <operation>`, optionally `<spec>:` in front to scope it to
one spec; text after ` #` is a comment for people. The gate then fails only on
groups no entry covers (`ApiFuzzReport.newFindingGroups()`); accepted groups
are marked in `report.md`, and entries that matched nothing in a completed run
are listed under "Accepted findings no longer seen" — fixed or renamed, and due
for removal. The outcome stays `FINDINGS` while any finding occurs, accepted or
not: acceptance changes the verdict, not the facts.

## Running It

The module is inert unless `forge.api-fuzz.enabled=true`. It belongs in an explicit environment profile job rather than the default build.

```java
@Autowired
ApiFuzzRunner fuzzer;

@Test
void theStagingApiPassesFuzzing() {
    fuzzer.assertHealthy();
}
```

## Stateful Testing

Stateful testing is available as an opt-in phase (`stateful` in `phases`) from Schemathesis. TestForge builds no producer/consumer inference of its own.
