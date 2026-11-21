# Research an API, then use it in a native load scenario

This is the adapted project's composition root. Production `module-load` still depends
only on `core`; it neither reads discovery DTOs nor needs the research modules to run.

```text
snapshot bundled OpenAPI + selected operations + native scenario assets
  → module-api-discovery + module-api-explorer
  → validate their JSON artifacts
  → Grafana openapi-to-k6 generates a native TypeScript client
  → module-load executes the project-owned k6 scenario
```

The upstream [Grafana generator](https://github.com/grafana/openapi-to-k6) is pinned to
0.4.1 in package-lock. Its installation is local (`npm ci --ignore-scripts`), never
global. It owns client generation; TestForge adds snapshotting, task ordering, file
validation and evidence. The upstream URL helper is vendored with checksum/attribution,
so running the resulting native bundle does not fetch JavaScript from a CDN.

## One explicit workflow

Requires Node.js 22+, npm, Java/Gradle and the pinned k6 binary. Run from the repository root:

```bash
./gradlew :example-tests:apiLoadTest \
  -PloadBaseUrl=http://127.0.0.1:8080 \
  -Pk6Executable=/absolute/path/to/k6
```

This example expects the synthetic API described in `docs/examples/api-load/openapi.json`:
`GET /health` returning `{"status":"UP"}` and `GET /api/v1/tasks` returning an `items`
array. Use the acceptance command below to run it without providing a server.
The document also declares POST, which the default selection/scenario never calls.

The task creates a fresh `build/api-load/<uuid>/` directory, snapshots the inputs,
researches the explicitly selected target, verifies the selected operations passed,
calls the upstream generator and runs the native scenario. No stage is attached to
the default `build`/`test`. To stop after research/client preparation, invoke
`:example-tests:prepareApiLoadScenario` instead.

For a real project, supply these inputs:

| Property | Input |
|---|---|
| `apiLoadSpec` | Local bundled OpenAPI 3 YAML/JSON; default `docs/examples/api-load/openapi.json` |
| `apiLoadOperations` | JSON array of explicit `METHOD /path` keys; default `docs/examples/api-load/operations.json` |
| `apiLoadScenarioDir` | Project-owned assets directory with native `scenario.ts`; default `docs/examples/api-load/scenario` |
| `apiLoadAllowWrites` | `false`; writes require explicit `true` and a reviewed business flow |
| `apiLoadRunDir` | Optional fresh output directory; defaults to a new UUID under build/api-load |

Existing `loadRate`, `loadTimeout`, `loadHtmlReport`, Prometheus/Grafana and output-dir
properties still apply. `loadDuration` and `loadIterations` are environment parameters
of this example's native script, not a new workload language. For a quick check use
`-PloadIterations=3 -PloadHtmlReport=false`; for continuous traffic the sample defaults
to 2 native business iterations/s over 10s, two requests per iteration.

The generator creates `prepared/client.ts`. `scenario.ts` imports the generated client
and explicitly chooses methods, checks, auth, sequencing, data and k6 options. Its code
is copied unchanged from the project's scenario directory. For another OpenAPI title
or operationId, adjust the class/method imports in your native scenario to the generated
SDK; this workflow does not infer a business process from an endpoint list.

External `$ref` files must be bundled with an existing OpenAPI bundler before this
workflow. The snapshot rejects remote/file references to keep research and generation
on one reproducible input document. OpenAPI examples and scenario/data files are trusted
project inputs: keep credentials and real customer data out of them.

## Separation and failure semantics

- `apiLoadResearch` uses the existing modules in an isolated project test assembly.
  The upstream selector is narrowed by exact operation keys, so choosing GET on one
  path and POST on another never probes the unselected cross-product. It writes
  the ordinary catalog/report files. No producer module was changed.
- Preparation checks the **selected** operations exist and have a PASSED observation.
  A skipped/unreachable/contract-mismatching operation stops before client generation
  or load. A documented non-200 response can pass research; the scenario owns its
  performance checks and expected business statuses.
- Each phase uses the same input snapshot. Entry document, scenario assets, producer
  reports and the complete native bundle are checksummed. Retargeting or changing
  prepared code requires a new workflow. Checksums detect accidental edits/staleness;
  this is not a signature or a sandbox for untrusted scripts.
- The operations list declares research prerequisites. It does **not** constrain
  arbitrary requests a trusted k6 script can make, nor define workload percentages.
- Resolved URLs, response bodies and request credentials from observations are not
  copied into the load script. Credentials are supplied afresh through `LOAD_API_TOKEN`.
  Adapt the project's research assembly to supply normal explorer parameter/body
  settings where required. It intentionally does not import unrelated API registries
  from the example application's YAML configuration.
- Prepared client/scenario/helper files can run directly through k6. Discovery and
  explorer are not required on the runner machine. For independent existing scenarios,
  keep using `:example-tests:loadTest` or the `LoadRunner` API without this workflow.

The producer reports currently have their existing JSON layouts. The project-level
adapter validates required fields; an upstream field change is a project-workflow
integration change, never a reason to couple module-load to producer classes.

## Artifacts

```text
build/api-load/<uuid>/
  inputs.json                  snapshot checksums
  input/                       bundled OpenAPI, operation selection, source scenario/assets
  discovery/                   existing module-api-discovery reports/catalog
  exploration/                 existing module-api-explorer reports/observations
  research.json                binding between document, target and research reports
  generated/                   original upstream generator output
  prepared/                    portable native client + scenario + vendored helper
    provenance.json            input/research/runtime hashes and selected-operation metadata
  load/<load-run-id>/           normal module-load reports, plus provenance.json
  verification/                dedicated JUnit/HTML stage reports
```

## Verify locally

```bash
npm ci --prefix tools/api-load --ignore-scripts --no-audit --no-fund
npm test --prefix tools/api-load
python3 tools/api-load/acceptance.py --k6 /absolute/path/to/k6
```

The opt-in acceptance starts its own loopback API, performs two research calls and
six load requests, then runs the prepared bundle directly through k6. Finally it
breaks the API contract and proves research prevents load. It checks no POST was sent.
With the local observability stack running, add `--prometheus http://127.0.0.1:9090`
and optionally `--grafana ...`. Logs/evidence are retained under build/api-load-acceptance.
The manual CI workflow also runs this acceptance; it never targets a public/shared API.
