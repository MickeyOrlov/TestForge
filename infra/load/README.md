# Local load observability

Pinned and tested: k6 **2.3.0**, Prometheus **3.15.1**, Grafana OSS **13.3.0**.
The Compose file pins image digests as well as versions. These are separate external
services; TestForge contains neither a metrics store nor a dashboard server.

```bash
docker compose -f infra/load/compose.yml up -d
```

- Grafana: <http://127.0.0.1:3000/d/testforge-load/testforge-load>
- Prometheus: <http://127.0.0.1:9090>

Grafana provisions its datasource and eight panels from this directory. It offers
anonymous **Viewer** access on loopback for local development, with initial admin
creation/login form disabled. This is a local stack, not a shared deployment recipe.
Both services bind only `127.0.0.1`. For different host ports:

```bash
LOAD_PROMETHEUS_PORT=19090 LOAD_GRAFANA_PORT=13000 \
  docker compose -f infra/load/compose.yml up -d
```

Use the corresponding URLs in the Gradle properties below. A datasource inside Docker
uses `http://prometheus:9090`; a k6 binary on the host uses the published host port.

## Run a scenario

Install the pinned k6 binary separately. Pick the target explicitly; the example
calls `/health` and sends a small ramp of native k6 iterations over 25 seconds.

```bash
./gradlew :example-tests:loadTest \
  -Pk6Executable=/absolute/path/to/k6 \
  -PloadBaseUrl=http://127.0.0.1:8080 \
  -PloadPrometheusEnabled=true
```

This task is excluded from `build`/`test`, always executes when invoked, and refuses
to start without `loadBaseUrl` or `TESTFORGE_LOAD_BASE_URL`. Select another native
JS/TS script using `-PloadScript=load/orders.js` (resolved from the repository root).

| Property | Default |
|---|---|
| `loadRate` | `2` native iterations/second at the steady stage of the sample |
| `loadTimeout` | `15m` |
| `loadHtmlReport` | `true`; use `false` for short smoke scenarios |
| `loadOutputDir` | `build/load` |
| `loadPrometheusEnabled` | `false` |
| `loadPrometheusUrl` | `http://127.0.0.1:9090` |
| `loadRemoteWriteUrl` | `<loadPrometheusUrl>/api/v1/write` |
| `loadGrafanaUrl` | `http://127.0.0.1:3000` |
| `loadProject` / `loadEnvironment` | `testforge` / `local` |

`LOAD_API_TOKEN` becomes the native script's `__ENV.API_TOKEN` if needed; no credentials
go in CLI arguments. `LOAD_PROMETHEUS_TOKEN` supplies the remote-write bearer token.
The sample health scenario itself requires no authentication. For a custom application,
the script owns login, refresh and workload semantics; supplying a token does not
automatically add an Authorization header to native requests.

The resulting `report.md` and `report.json` include a link with project/environment
and the exact time range selected. Run IDs are absent from metric labels by default,
so repeated runs reuse series. To isolate concurrent runs and exact per-run counter
totals, opt in with `-PloadPrometheusRunIds=true` (or
`forge.load.prometheus.run-ids: true`); report links then also select the run UUID.
Without it, the dashboard selects All runs and window totals over reset/reused counters
are not exact per-run counts. Use the native report for those counts.

Service volumes preserve history across restarts, bounded by Prometheus's **7 days /
512 MiB** retention. Run IDs add a set of series on every invocation; use bounded
retention on external Prometheus installations too. Remote-write runs exclude native
URL/name and free-text error/group/check system tags. Custom tags and scenario names
remain trusted script inputs; avoid request/user IDs, URLs and credentials. Do not
reuse the reserved `project`, `environment`, `run_id`, `generator` labels in script
tags: explicit script tags take precedence over remote-write static labels.

Native HTML is off by default; request `-PloadHtmlReport=true` explicitly after reviewing
the scenario's names/tags and use a run long enough to generate the dashboard export.

## Verify the whole path without a target service

```bash
./gradlew :module-load:observabilityTest -Pk6Executable=/absolute/path/to/k6
```

The opt-in acceptance test starts its own loopback HTTP fixture and sends six requests.
It checks that Prometheus received exactly those six under the run UUID, that Grafana
provisioned the dashboard and that every panel query returns data. Evidence is retained
under `module-load/build/load-observability/<run-id>/`, including native HTML and
`telemetry-check.json`. It requires the Compose services above; it never loads a public
or shared API. The manual GitHub workflow `load-observability.yml` reproduces this check.

## What the charts mean

- RPS: sum of counter rates for the selected run(s).
- p95/p99: `histogram_quantile` **after summing native histogram rates**, in seconds.
  Per-generator percentile gauges are never averaged.
- HTTP error ratio: worst current native series, explicitly not an average across workers.
- VUs: instantaneous active users and provisioned capacity from the native gauges; dropped iterations: counter rate, zero only when there
  is measured HTTP traffic and k6 omitted an all-zero dropped series.
- Requests/iterations: cumulative run counters observed in the selected window. They
  are not an estimate of the exact number of events in an arbitrary sub-window.

Prometheus remote write remains an experimental k6 output. Local JSON thresholds are
the load gate's source of truth; the optional telemetry sink is not a second gate.
A remote-write outage can leave the native result intact and the dashboard empty.
The acceptance test verifies delivery explicitly; it does not imply delivery is checked
on every production run. Inspect Prometheus availability when using the dashboard.

This dashboard observes the generator's measurements. To diagnose a real service,
configure its existing exporters as additional Prometheus scrape jobs. For a Spring
service exposing Actuator Prometheus on the host, a Docker Desktop target is commonly
`host.docker.internal:<port>` and metrics_path `/actuator/prometheus`. Supply the
service's actual endpoint/authentication; TestForge does not discover it or enable
Actuator automatically. CPU, database metrics and traces require their own exporters.

## Stop / reset

```bash
docker compose -f infra/load/compose.yml down
# To intentionally delete this stack's retained history:
docker compose -f infra/load/compose.yml down --volumes
```

Distributed k6 execution remains the upstream k6 Operator's responsibility. This local
adapter does not pretend that independent worker thresholds constitute a global p95
gate. Introduce Kubernetes only after one generator's capacity has been measured and
a concrete cluster/job ownership model is available.

## API research composition

`:example-tests:apiLoadTest` adds the optional research → upstream client generation →
native scenario sequence at the project level. See [tools/api-load](../../tools/api-load/README.md).
It reuses this same metrics stack and adds no producer dependency to module-load.
