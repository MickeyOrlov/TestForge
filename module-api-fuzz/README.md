# module-api-fuzz

Give it an OpenAPI document and an environment; get back a rigorous, schema-aware API fuzzer report.

This module is a **thin adapter** that runs the external [Schemathesis](https://schemathesis.readthedocs.io/) CLI. Schemathesis owns generation, coverage, negative testing, shrinking, stateful testing, replay, and reporting. TestForge owns configuration, the safety policy, command construction, process execution, report ingestion, and run evidence. **TestForge contains no fuzz engine.**

## Installation

Schemathesis is **not** a Gradle/build dependency and is never installed by `./gradlew build`. It must be installed separately. The tested version is 4.24.3.

For local use or in a reproducible CI script, install it via `uv`:

```bash
uv tool install schemathesis==4.24.3
# or run directly: uvx schemathesis==4.24.3
```

Note: The executable installed is `st`.

## Configuration

Specs are not configured here; the fuzz module reads the registry `module-api-discovery` already owns.

```yaml
forge:
  api-discovery:
    specs:
      demo:
        location: classpath:/openapi/demo-api.yaml
  http:
    base-url: https://api.staging.example.test
  api-fuzz:
    enabled: false                # default; nothing happens until this is true
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
```

## Safety

The safety policy enforces a strict two-key rule: `GET`, `HEAD`, and `OPTIONS` need no opt-in, but a mutating method needs **both** the method listed in `methods` **and** `allow-unsafe-methods=true`.

Enforcement needs **two** mechanisms, not one. Schemathesis's coverage phase otherwise emits requests with methods that are not in the spec (a GET-only configuration was observed emitting TRACE). TestForge passes `--include-method` to the CLI **and** generates a `schemathesis.toml` with `unexpected-methods = []` under `[phases.coverage]`.

Read [docs/api-fuzz-prototype-analysis.md](../docs/api-fuzz-prototype-analysis.md) for a deeper analysis.

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

Exit code 1 covers both API findings and infrastructure failure. This is why the module classifies outcomes from the NDJSON report stream: a `NonFatalError` event means execution failure, not an API finding.

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
