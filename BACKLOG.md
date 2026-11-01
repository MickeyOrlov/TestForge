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

## Priority 2: stronger database module (shipped)

**Module:** `module-db`

Goal: make gray-box DB checks comfortable for multi-service systems.

Named datasource selection, column type drift and nullable drift are delivered;
see the Delivered section of docs/ROADMAP.md. What remains:

Inheritance (`@MappedSuperclass`, `SINGLE_TABLE`, `JOINED`,
`TABLE_PER_CLASS`), pluggable naming (`SchemaNamingStrategy`) and index and
foreign-key drift against entity mappings are delivered without ORM internals,
proven on H2 and PostgreSQL. Nothing planned beyond that.

## Priority 3: richer shared mock diagnostics (shipped)

**Module:** `module-mock`

Scope matching by body JSON path, header, query parameter or cookie, scoped
journal assertions, readable mismatch diagnostics and failure artifacts are
delivered. Revisit only for a concrete adaptation need (for example, matching
a scope that the system under test splits across two fields).

## Priority 4: messaging abstraction beyond Kafka (shipped)

Plan: [docs/messaging-layer-roadmap.md](docs/messaging-layer-roadmap.md). The neutral core is in `core`; `module-rabbitmq` is the second adapter. Both write the same failed-test traffic report; reviewed against a real broker.

**Modules:** `module-kafka`, possible future `module-messaging`

Goal: keep Kafka support, but make the probe/buffer/filter model reusable for
other brokers.

- Extract broker-neutral concepts: message envelope, filter, buffer, probe.
- Keep Kafka as the first concrete adapter.
- Add extension points for RabbitMQ, JMS, SQS, or other queue providers.
- Preserve the current rule: message collection and contract validation remain
  separate modules.

## Priority 5: prepared data pool refill (shipped)

**Module:** `module-data`

Config preload, background refill, per-variant statistics and the
`pool-stats.json` run artifact are delivered; objects stay one-use only.

## Priority: stateful exploration on top of module-api-explorer (shipped)

**Module:** `module-api-explorer` (v1 shipped: stateless, safe-by-default)

V1 explores each operation independently. The next useful step is the one
RESTler earns its value from — using what one operation returned to reach the
next one.

- Delivered (step 1): declarative extraction rules (`forge.api-explorer.extract`),
  producer-before-consumer ordering, a per-rule outcome in the report. See
  [docs/api-explorer-stateful-design.md](docs/api-explorer-stateful-design.md).
- Delivered (step 2): producer/consumer links inferred from the document and
  proposed in the report as ready-to-paste rules, never applied on their own.
- Delivered (step 3): write chains — a `POST` from a body template producing
  the id a `GET`/`DELETE` consumes, behind both write keys, `DELETE` last.
- Delivered (step 4): replay from an earlier run's observations
  (`forge.api-explorer.replay-from`), no network, honest about what a
  recording cannot reproduce. Stateful exploration is complete.
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

- `module-gherkin` (in progress, [plan](docs/gherkin-layer-roadmap.md)):
  optional Cucumber steps for organizations that standardize on feature
  files. Step 1 shipped: scenario lifecycle, variables, HTTP steps.
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
