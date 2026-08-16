# Unified reporting artifacts — design note

Backlog Priority 1. This note is the contract the implementation tasks are
written against; read it before touching `module-reporting`.

## The question a failed CI run should answer

> Where are the diagnostics for this run?

Today the answer depends on knowing which module produced them:
`build/api-fuzz`, `build/api-explorer`, `build/playwright-artifacts`,
`build/appium-artifacts`, `build/contract-monitor/current`, and so on. Each
module already owns a directory and writes its own files. That part works and
is **not** being migrated.

What is missing is a single run-scoped index: one place that says what was
produced, by whom, and where it is.

## Shape

```
producer module (flow, mock, reporting, web, mobile, api-*)
   |  writes its own diagnostic file, as it already does
   |  then describes it through a neutral seam
   v
ArtifactSink            <- interface, lives in `core`
   |
   v
module-reporting        <- the only implementation
   +-- run-scoped directory
   +-- manifest.json (deterministic)
   +-- summary.md
   +-- optional Allure adapter
```

**The seam lives in `core`, not in `module-reporting`.** That is the whole
trick, and it is what keeps the dependency graph honest:

- producers already depend on `core` (all 16 modules do) — publishing costs
  them **no new module dependency**;
- `module-reporting` depends on `core` and implements the interface;
- `module-reporting` never imports Playwright, Appium, WireMock, Kafka or
  Allure-the-requirement, and never learns how to query a producer;
- deleting `module-reporting` leaves every producer compiling, because they
  only ever referenced `core`. The sink resolves to a no-op.

`core` stays thin by its own rule (AGENTS.md: a class belongs there when more
than one module needs it). Several modules need this one.

## What an artifact is

A descriptor, not a container. Deliberately small:

| Field | Why it is here |
|---|---|
| `source` | which module produced it — the thing you cannot recover later |
| `category` | coarse kind (`resource-usage`, `flow-path`, `mock-diagnostics`, …) |
| `name` | logical name, unique within source+category |
| `file` | where it actually is |
| `mediaType` | so a renderer/attachment knows what to do |
| `createdAt` | ordering across a run |
| `metadata` | small, producer-supplied, already-safe key/values |

Rejected: test ids, scenario ids, severity, retention, tags, size, checksum.
None of them are needed to answer the question above, and every one of them is
a field a future producer would have to fill in with a guess.

## No RunReport god object

There is no mutable aggregate that modules mutate during a run. Artifacts are
appended to a thread-safe collection; the *report* is the manifest file written
from it. Nothing needs to hold the run open, and nothing needs to be injected
into a producer just so it can register.

## Isolation

One run directory, allocated once: `<artifacts-dir>/<runId>/`. Per-source
subdirectories, and collision-free file names within them. Parallel tests
publishing simultaneously must not overwrite each other — that is a test
requirement, not a comment.

## Failure semantics — the rule that matters

Reporting is best-effort and **secondary**. A diagnostic that cannot be written
must never replace the failure that caused the run to be interesting.

- every sink method swallows its own failure, logs it at WARN, and records it
  as a reporting problem;
- no sink method throws;
- a test proves that a product assertion failure survives a failing artifact
  write, with the original assertion still the reported cause.

If reporting can turn "the API returned 500" into "attachment failed", the
feature is worse than not having it.

## Security

Artifact infrastructure preserves secrets by accident. Rules:

- the manifest stores paths **relative to the run root**, never absolute —
  absolute paths leak usernames and directory layout into CI output;
- metadata is producer-supplied and must already be safe; the manifest never
  copies request bodies, headers, URLs with credentials, or environment values
  into itself;
- if a producer already redacts (module-http's `Redactor`, the explorer's and
  fuzz module's redacted observations), that property is preserved by not
  re-reading the raw source.

No second redaction framework. Redaction stays where the data and its context
already live.

## Allure

Optional, exactly as `AllureResourceAttachments` is today: `compileOnly`, used
only if the consuming test module puts Allure on its runtime classpath. Absent
Allure, everything else works and the build does not need it.

## CI without Allure

`manifest.json` plus `summary.md` in the run directory. That is the supported
path; Allure is a convenience on top.

## Explicitly not built

No server, no artifact database, no remote/S3 storage, no web UI, no telemetry
or tracing backend, no log aggregation, no event bus, no plugin framework, no
CI-provider integration, no Allure replacement. `module-reporting` is a
directory, a manifest and a small interface — not infrastructure.
