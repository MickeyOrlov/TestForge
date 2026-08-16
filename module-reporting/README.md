# module-reporting

Unified diagnostic reporting and JVM resource monitoring for TestForge test runs.

## Overview

`module-reporting` manages a run-scoped artifact directory, generates deterministic `manifest.json` and `summary.md` indices, collects JVM memory and CPU metrics, and provides optional Allure attachment helpers.

It is **not** an observability platform, telemetry daemon, tracing backend, or log aggregator. It is a lightweight, run-scoped directory layout and file index.

## Architectural Seam (`ArtifactSink`)

The artifact publishing interface (`ArtifactSink`) lives in **`core`**, not in `module-reporting`.

### Why the seam lives in `core`
1. **Zero extra dependencies for producers**: All test modules already depend on `core`. Publishing diagnostics costs producer modules no new Gradle or Maven dependency.
2. **Deletable module**: `module-reporting` can be removed from a project or disabled without breaking producer compilation or execution.
3. **No-op fallback**: When `module-reporting` is absent or disabled (`forge.reporting.artifacts.enabled=false`), `ArtifactSink.NO_OP` is bound. Producers write to a temporary/absent path and perform no-op registrations without null checks or exceptions.

## Test Artifact Descriptor (`TestArtifact`)

A `TestArtifact` (defined in `core`) is a descriptor pointing to a diagnostic file written on disk:

| Field | Type | Description |
|---|---|---|
| `source` | `String` | Producing module identifier (e.g. `"module-flow"`, `"module-mock"`, `"module-reporting"`) |
| `category` | `String` | Coarse diagnostic category (e.g. `"flow-path"`, `"mock-diagnostics"`, `"resource-usage"`) |
| `name` | `String` | Logical artifact name, unique within source and category (e.g. `"flow-checkout-path.txt"`) |
| `file` | `Path` | Absolute or relative path to the diagnostic file on disk |
| `mediaType` | `String` | MIME type (e.g. `"application/json"`, `"text/plain"`, `"image/png"`) |
| `createdAt` | `Instant` | Timestamp when the artifact descriptor was created |
| `metadata` | `Map<String, String>` | Producer-supplied key-value metadata (must be safe / pre-redacted) |

## Configuration

All configuration lives under the `forge.reporting` prefix (`ReportingProperties`):

```yaml
forge:
  reporting:
    resource-monitor:
      enabled: true
      period: 2s
    artifacts:
      enabled: true
      dir: build/testforge-artifacts
      run-id: run-123
```

- **`forge.reporting.artifacts.enabled`** (boolean, default: `false`): Enables run-scoped artifact collection, directory layout management, `RunArtifactSink`, `manifest.json`, and `summary.md` generation.
- **`forge.reporting.artifacts.dir`** (Path, default: `build/testforge-artifacts`): Base output directory for test run artifacts.
- **`forge.reporting.artifacts.run-id`** (String, optional): Custom run identifier. If omitted, a time-sortable run ID is generated automatically (e.g. `20260816-001435-123-a1b2c3`).
- **`forge.reporting.resource-monitor.enabled`** (boolean, default: `false`): Enables background JVM memory and CPU sampling.
- **`forge.reporting.resource-monitor.period`** (Duration, default: `2s`): Sampling interval for the resource monitor.

## Run Directory Layout & Artifact Index

When `forge.reporting.artifacts.enabled=true`, `ArtifactRunLayout` organizes files into a single run-root directory:

```
build/testforge-artifacts/20260816-001435-123-a1b2c3/
├── manifest.json
├── summary.md
├── module-flow/
│   └── checkout-flow.txt
├── module-mock/
│   └── unmatched-requests.json
└── module-reporting/
    └── resource-usage.txt
```

### `manifest.json` Format
At application context shutdown, `ArtifactManifestWriter` deterministically writes `manifest.json` in the run root:

```json
{
  "runId" : "20260816-001435-123-a1b2c3",
  "artifactCount" : 3,
  "reportingProblems" : [ ],
  "artifacts" : [ {
    "source" : "module-flow",
    "category" : "flow-path",
    "name" : "checkout-flow.txt",
    "path" : "module-flow/checkout-flow.txt",
    "file" : "module-flow/checkout-flow.txt",
    "mediaType" : "text/plain",
    "createdAt" : "2026-08-16T00:14:35.123Z",
    "metadata" : {
      "executionTimeMs" : "142"
    }
  } ]
}
```

Artifacts are deterministically ordered by `createdAt` -> `source` -> `category` -> `name`.

### `summary.md` Format
`ArtifactSummaryWriter` writes `summary.md` in the run root as a Markdown index:

```markdown
# Test Run Summary: 20260816-001435-123-a1b2c3

**Artifact Count:** 3

## Diagnostics by Source

### module-flow

- **checkout-flow.txt** (flow-path): `module-flow/checkout-flow.txt`

### module-reporting

- **resource-usage.txt** (resource-usage): `module-reporting/resource-usage.txt`
```

## Reading Failed Runs in CI Without Allure

Allure is entirely optional. In standard CI environments (GitHub Actions, GitLab CI, Jenkins):
1. Archive the directory `build/testforge-artifacts/` as a build artifact.
2. Open `summary.md` in the run directory for a human-readable index of all diagnostics grouped by producing module.
3. Inspect or parse `manifest.json` programmatically to locate diagnostic files.

## Optional Allure Integration

Allure support (`AllureArtifactAttachments`, `AllureResourceAttachments`) is compiled against `io.qameta.allure:allure-java-commons` as a **`compileOnly`** dependency.

- If Allure is on the runtime classpath of the test module:
  ```java
  AllureArtifactAttachments.attach(artifact);
  // or attach all artifacts from the sink
  AllureArtifactAttachments.attachAll(runArtifactSink.artifacts());
  ```
- If Allure is absent at runtime, calls to Allure attachment helpers safely catch `Throwable`/`LinkageError` and log warnings without throwing exceptions or failing tests.

## Failure Semantics (Best-Effort Rule)

**CRITICAL CONTRACT:** Reporting is best-effort and secondary. A failure in reporting must **never** replace or mask the underlying test failure.

- `ArtifactSink` implementations swallow exceptions during directory creation or file writing, log warnings, and record `ReportingProblem` entries.
- `ArtifactReportingLifecycle` swallows errors during shutdown reports generation.
- If an artifact write fails, the original test assertion remains the reported test failure.

## Security Rules

1. **Relative Paths Only**: `manifest.json` and `summary.md` store paths relative to the run root (using `ArtifactRunLayout.relativize(...)`). Absolute local paths (e.g. `/Users/username/...`) are never written to manifests, preventing credential and username leaks in CI logs.
2. **Producer-Supplied Safe Metadata**: The manifest writer copies `metadata` key-value pairs directly as supplied by the producer without reading raw file contents.
3. **No Payload Copying or Re-Redaction**: Redaction is the responsibility of producing modules (e.g. `module-http`'s `Redactor`). Reporting does not re-read or re-parse files, ensuring existing redactions remain intact.

## Module Adoption Guide

To adopt `ArtifactSink` in a new or existing module:

1. **Inject `ArtifactSink`** in your Spring auto-configuration or component constructor:
   ```java
   @Bean
   public MyModuleService myModuleService(ArtifactSink artifactSink) {
       return new MyModuleService(artifactSink);
   }
   ```
2. **Write small text/JSON diagnostics directly**:
   ```java
   artifactSink.write("module-mymodule", "diagnostics", "details.json", "application/json", jsonContent);
   ```
3. **Register files already written by your module**:
   ```java
   Path reportFile = artifactSink.directoryFor("module-mymodule").resolve("report.html");
   Files.writeString(reportFile, htmlContent);

   artifactSink.register(new TestArtifact(
       "module-mymodule",
       "report",
       "report.html",
       reportFile,
       "text/html",
       Instant.now(),
       Map.of("status", "FAILED")
   ));
   ```
