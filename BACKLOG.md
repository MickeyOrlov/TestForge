# TestForge Backlog

This backlog tracks the next useful expansions after the current v1 template
work. It is intentionally product-neutral: no client, employer, or domain
specific names belong here.

## Priority 1: unified reporting artifacts (shipped; producers migrated)

**Module:** `module-reporting` (and producing modules)

Goal: make CI output easy to inspect after a failed run.

- **Shipped Core Abstraction & Infrastructure**:
  - Neutral `ArtifactSink` interface in `core` with `TestArtifact` descriptors and `ArtifactSink.NO_OP` fallback.
  - Run-scoped directory layout (`ArtifactRunLayout`) with time-sortable run IDs and collision-free file naming.
  - Thread-safe collector (`RunArtifactSink`) and deterministic `manifest.json` (`ArtifactManifestWriter`) and `summary.md` (`ArtifactSummaryWriter`) generation on context shutdown (`ArtifactReportingLifecycle`).
  - JVM resource monitor (`ResourceUsageMonitor`) publishing stats via `ArtifactSink`.
  - Optional Allure attachment helpers (`AllureArtifactAttachments`, `AllureResourceAttachments`).
  - Best-effort failure semantics and security rules (relative paths only, producer-supplied safe metadata, no payload copying).
- **Migrated Producer Modules**:
  - `module-reporting`: resource usage statistics.
  - `module-flow`: execution path reporting on flow completion or failure.
  - `module-mock`: scoped mock diagnostics and unmatched request logs.
  - `module-db-contract`, `module-api-discovery`, `module-api-explorer`,
    `module-contract-monitor`, `module-api-fuzz`: run reports registered from
    their own `output-dir` through `ArtifactSink.registerFile`, with `location`
    metadata for relative paths.
  - `module-web-playwright`, `module-mobile-appium`: failure screenshots,
    traces and page sources registered per failed test.
- **Remaining**:
  - `module-db` writes no diagnostic files (SQL goes to the logger), so there is
    nothing to register; revisit only if it gains a file-based report.
  - `module-api-codegen` output is generated source, not a diagnostic; its
    `report.json` could be registered if a team asks for it.
  - The Playwright registration path runs only with a real browser
    (`browsersTest`); it has no offline test.
  - Manifest paths for files outside the run root are file names only;
    `location` metadata carries the real path. A first-class field would be a
    manifest format change.

## Priority 2: stronger database module

**Module:** `module-db`

Goal: make gray-box DB checks comfortable for multi-service systems.

Named datasource selection, column type drift and nullable drift are delivered;
see the Delivered section of docs/ROADMAP.md. What remains:

- Expand schema drift checks to indexes and foreign keys where practical.
- Decide how to support inheritance and custom naming strategies without
  pulling the module into heavy ORM internals.

## Priority 3: richer shared mock diagnostics

**Module:** `module-mock`

Goal: make scoped WireMock failures explain themselves.

- Support scope matching by request body JSON path, header, query param, and
  cookie.
- Add request journal assertions for scoped traffic.
- Dump scoped stubs and unmatched requests as failure artifacts.
- Add readable mismatch diagnostics: why a scoped stub did not match the
  incoming request.

## Priority 4: messaging abstraction beyond Kafka

**Modules:** `module-kafka`, possible future `module-messaging`

Goal: keep Kafka support, but make the probe/buffer/filter model reusable for
other brokers.

- Extract broker-neutral concepts: message envelope, filter, buffer, probe.
- Keep Kafka as the first concrete adapter.
- Add extension points for RabbitMQ, JMS, SQS, or other queue providers.
- Preserve the current rule: message collection and contract validation remain
  separate modules.

## Priority 5: prepared data pool refill

**Module:** `module-data`

Goal: make expensive prepared objects more reliable in long CI runs.

- Add optional background refill based on pool thresholds.
- Add YAML configuration for preload variants and counts.
- Publish pool metrics through `PoolEventListener`.
- Report cold misses and exhausted variants in CI artifacts.
- Keep prepared objects one-use only; do not add release/reuse semantics.

## Priority: stateful exploration on top of module-api-explorer

**Module:** `module-api-explorer` (v1 shipped: stateless, safe-by-default)

V1 explores each operation independently. The next useful step is the one
RESTler earns its value from — using what one operation returned to reach the
next one.

- Extract values from recorded responses through a declarative rule, not a
  heuristic. The observation model already stores the redacted body and the
  resolved identity of every call.
- Infer producer/consumer links between operations (an id a `POST` returns
  satisfying a `GET`'s path parameter) and expose an inferred value as a new
  `ValueSource` — nothing that reads an observation should need to change.
- Sequence requests only once inference exists. An ordering with no data flow
  between steps is just a slower independent run.
- Reproducible replay from recorded observations, as a second
  `ExchangeExecutor` implementation. The interface is already that seam.
- Deterministic schema-aware fuzzing is now delegated to Schemathesis
  (`module-api-fuzz`), which already supports stateful testing capabilities.

Request bodies stay out of the explorer until extraction lands. Synthesizing
one means guessing at business meaning, and a wrong guess against a write
endpoint is exactly what the safety policy exists to prevent.

## Future modules

These are useful, but should stay behind the core CI/staging improvements.

- `module-gherkin`: reusable Cucumber/Gherkin fragments for organizations that
  already standardize on feature files.
- `module-api-fuzz`: continuous fuzzing orchestration via `st fuzz` (currently
  delegates schema-aware generation to `st run`, but continuous execution is
  deferred).
- TestForge Gradle bootstrap plugin: compose discovery, code generation,
  exploration, and fuzzing only after each remains useful independently.

## Keep Stable For Now

These modules are currently focused enough. Expand only when a real adaptation
requires it.

- `core`: keep thin; shared classes only when at least two modules need them.
- `module-contract`: keep the JSON Schema facade; do not grow a homemade DSL.
- `module-flow`: keep state-machine setup small and deterministic.
- `module-web`: keep prewarm focused; browser testing belongs in
  `module-web-playwright`.
