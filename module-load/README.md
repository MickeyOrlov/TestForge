# module-load

An opt-in adapter over **k6 OSS**, tested with **2.3.0**. TestForge launches a local
binary and publishes its result; k6 owns workload scheduling, HTTP, measurements and
thresholds. No load engine or second scenario language is implemented here.

## Installation and usage

Install the official [k6 binary](https://grafana.com/docs/k6/latest/set-up/install-k6/)
separately and pin it in your environment. Gradle never installs or downloads k6.
As a library, use `testImplementation "io.testforge:module-load:1.3.0-SNAPSHOT"`.

```yaml
forge:
  load:
    enabled: false                    # true only in an explicitly selected load profile/job
    command: k6                       # one executable, no shell command or appended flags
    expected-version: 2.3.0            # mismatch prevents traffic
    script: ./load/orders.js           # trusted filesystem JS/TS; imports remain native k6
    base-url: http://127.0.0.1:8080     # explicit target; no userinfo, query or fragment
    output-dir: build/load
    timeout: 15m                      # bounds the run; version probe capped at 5s
    html-report: false                # native HTML may contain script-defined names/tags
    environment:
      "[API_TOKEN]": ${LOAD_API_TOKEN} # optional; secrets are passed in the child environment
      "[LOAD_RATE]": "2"              # scenario-owned parameters, not a TestForge workload DSL
```

Enabling only creates beans. A test/job explicitly calls the runner:

```java
@Autowired LoadRunner load;

@Test
void theApiMeetsItsPerformanceThresholds() {
    LoadReport report = load.assertHealthy();
}
```

`run()` returns evidence for a failed run; `assertHealthy()` publishes it and throws
unless the outcome is PASSED. Disabled calls and invalid configuration fail before launch.
Neither method runs on Spring startup. Reports are written to a fresh UUID subdirectory:
`report.md`, `report.json`, and optionally `k6-report.html`; `ArtifactSink` registers them
when reporting is available. Neither module-reporting nor Grafana is required.

## Project validity policy

Defaults retain the HTTP CI checks, but projects can choose their own validity rules:

```yaml
forge:
  load:
    policy:
      require-http: true
      require-thresholds: true
      fail-on-dropped-iterations: true
```

Set `require-http: false` for a native non-HTTP scenario; base-url may then be omitted.
The generic report records zero measured HTTP requests/RPS and absent HTTP latency/error
rate in that case. Other protocol-specific metrics remain in native k6 outputs.
`require-thresholds: false` permits exploratory scripts without native thresholds;
`fail-on-dropped-iterations: false` retains dropped counts without invalidating an
exploratory run. Missing/malformed summaries, zero completed iterations and execution
errors still fail. A failed **native k6 threshold always fails**, regardless of policy.
The Gradle job exposes corresponding `loadRequireHttp`, `loadRequireThresholds` and
`loadFailOnDroppedIterations` properties. `loadExpectedVersion` selects the explicitly
expected k6 version; version changes still need the native compatibility checks.

## Optional API research workflow

The project-level [API → load workflow](../tools/api-load/README.md) snapshots a local
OpenAPI document, runs discovery/exploration, validates selected operations, invokes
Grafana's existing OpenAPI-to-k6 generator and executes a project-owned native scenario.
Invoke `:example-tests:apiLoadTest` with an explicit target. It adds **no production
module dependencies**: this module still consumes only ordinary k6 scripts/data, and
prepared bundles also run directly through k6 without TestForge.

## Prometheus and Grafana (optional)

Set `forge.load.prometheus.enabled: true` to add the native
`experimental-prometheus-rw` output. Defaults target the local Compose stack:

```yaml
forge:
  load:
    prometheus:
      enabled: true
      remote-write-url: http://127.0.0.1:9090/api/v1/write
      bearer-token: ${LOAD_PROMETHEUS_TOKEN:}
      project: testforge
      environment: local
      grafana-url: http://127.0.0.1:3000
      run-ids: false                   # opt in to one set of time series per run
```

k6 sends native histograms and static `project`, `environment`, `generator` labels,
with stale markers when it finishes. `run-ids: true` adds a run UUID label for isolated
counters and concurrent runs; it costs one set of time series per invocation. The
default reuses series across runs and selects All runs plus the report time range in
Grafana. Use distinct stable project/environment labels for independent generators,
or explicitly opt into run IDs. Window totals over reused/reset counters are not exact
per-run counts; use the native report or opt into run IDs for that purpose.
Credentials stay in the child environment. Each local invocation gets its own report
UUID and a Grafana link with its time range in `report.md`/`report.json`.
When remote write is enabled, the runner explicitly limits native system tags to
`proto,status,method,tls_version,scenario,expected_response,error_code`. It excludes
both `url` and `name` (k6's default name is a URL), plus free-text error/group/check
tags. Thresholds using excluded system tags must be adapted or run without remote
write. Custom tags and scenario names remain trusted script inputs: keep their values
bounded and free of credentials. Native load thresholds remain the gate; optional telemetry
availability is verified separately by `observabilityTest`.

See [infra/load](../infra/load/README.md) for the pinned Docker Compose stack,
provisioned dashboard, retention, datasource setup, `:example-tests:loadTest` and
`observabilityTest`. Prometheus/Grafana are independent external services and never
start during `build` or Spring initialization.

## Native scenario contract

Write normal k6 code. Use `__ENV.TESTFORGE_LOAD_BASE_URL` for the selected target,
and add this standard [handleSummary](https://grafana.com/docs/k6/latest/results-output/end-of-test/custom-summary/)
hook (or add the entry to your existing hook):

```javascript
export function handleSummary(data) {
  return { [__ENV.TESTFORGE_LOAD_SUMMARY]: JSON.stringify(data) };
}
```

The runner explicitly selects `--new-machine-readable-summary=false`; the consumed
contract is `metrics.<name>.values`, with `thresholds.<expression>.ok` booleans. This
is **not** the legacy `--summary-export` format. Do not replace the hook with that flag.
Version changes need the real k6 acceptance test before updating expected-version.

Under the default policy the script must emit completed `iterations`, `http_reqs`
with count/rate, and at least one threshold. TestForge copies counts, actual RPS, optional HTTP p95/error rate and
threshold totals from k6. It never recomputes percentiles. Failures of `check()` alone
do not fail k6: declare a native `checks` threshold. The sample in
[docs/examples/load-api.js](../docs/examples/load-api.js) shows thresholds and an open
ramping profile. Its timings are examples, not a product SLA. Ramp-up is included in
the sample's aggregates; use native scenario tags/thresholds for a steady-only assessment.

Outcomes:

| Outcome | Meaning |
|---|---|
| PASSED | Exit 0, valid summary, configured validity policy satisfied and all declared thresholds passed |
| THRESHOLDS_FAILED | Native thresholds failed (normally exit 99) |
| EXECUTION_ERROR | Start/version error or exit other than 0/99 |
| TIMED_OUT | Version probe or load process exceeded its timeout |
| CANCELLED | Calling thread interrupted; its interrupt flag is restored |
| INVALID_RESULT | Missing/malformed/empty summary, zero completed iterations, a validity policy violation, inconsistent exit 99, or missing requested HTML |

When a result is invalid, fix the script or generator capacity before interpreting latency.
An arrival rate counts **iterations**, not HTTP requests: one iteration can issue several.

## Process and evidence boundaries

The local executor drains output while k6 runs and keeps at most 8 KiB in memory. Raw
output is never written into diagnostics or exceptions. On timeout/interruption it
terminates the process and its observed descendants, including after a normal exit.
Descendants are sampled while the parent lives; a child created and orphaned between
samples may escape discovery. This portable Java adapter is not an OS process sandbox;
use an isolated job/container for stronger lifecycle guarantees. Windows environment
allowlisting preserves mixed-case names such as `Path`. Only a small OS environment
(PATH/HOME/temp/certificate paths) is inherited; CI credentials and ambient K6_* flags
are not. A generated empty config replaces ambient user configuration. `K6_*` and
`TESTFORGE_LOAD_*` entries are reserved; supply native options in the script.

Every run records timestamps, version, entry-script SHA-256, exit/outcome and selected
numeric metrics. It does not persist environment values, target URLs, arbitrary metric
names, group names or raw summary/console output. The temporary raw summary is deleted.
The entry hash does not cover imported modules/data; pin and version those alongside
your script. Prepare data/auth before the measured phase; Java beans and ScenarioContext
do not transfer into k6. Token refresh within the scenario belongs to the scenario.

Private summary cleanup removes nested files without following symbolic links. A
cleanup failure is recorded without suppressing the verdict or reports; an otherwise
passing run becomes INVALID_RESULT so leftover private data is not silently ignored.

Scripts are trusted executable code: they can ignore base-url, access explicit credentials,
import remote code or write files. This module is not a network allowlist or a sandbox.
Keep credentials out of source, metric tags/names and URLs. Opt into HTML only after
reviewing what your native dashboard contains. For debug output run the trusted scenario
directly with k6; raw logs are intentionally absent from CI evidence.

HTML uses a one-second dashboard period with its HTTP listener disabled. Very short
runs may not generate a native HTML report; use a run longer than three seconds when
requesting HTML, or leave html-report disabled for quick smoke runs.

## Verification and limits

```bash
./gradlew :module-load:test :architecture-tests:test
./gradlew :module-load:k6Test -Pk6Executable=/absolute/path/to/k6
```

The normal build uses a process stub and real local child JVMs for lifecycle tests.
`k6Test` is opt-in, requires the binary and runs three requests against its own loopback
server, then another three to verify threshold exit 99 and HTML output. It also verifies
a native non-HTTP scenario with explicitly relaxed validity rules. No target API
or public site is contacted. For an adapted project's real scenario, use its own explicit
load test task/profile. k6 is AGPLv3; no k6 sources or binaries are bundled in this JAR.

One local native k6 generator, HTTP aggregate reporting, optional Prometheus/Grafana
provisioning and independent project-level load/research tasks. Distributed orchestration, browser/messaging load, automatic translation
of JUnit tests and module-quality scoring remain separate work. Native k6 scenarios remain
usable directly outside TestForge.
