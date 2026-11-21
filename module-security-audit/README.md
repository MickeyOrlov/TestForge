# module-security-audit

An opt-in internal aggregator foundation. Current capability: **profile planning and import/aggregation of existing
normalized security assessments, and explicit trusted process/CLI foundation**. No scanner is installed or launched; no target,
DNS or local input file is accessed. A runnable plan is not a security verdict.
Full staged design: [security-audit-roadmap.md](../docs/security-audit-roadmap.md).

```yaml
forge:
  security-audit:
    enabled: true
    max-assets: 100
    max-jobs: 700
    max-requests: 1000
    timeout-seconds: 1200
    max-concurrent: 2
```

`SecurityAuditPlanner` is auto-configured; planning refuses the default disabled
configuration. Calling `plan` is explicit; enabling it triggers no work at startup.
Only core is a module dependency. No scanner library, Docker or hosted service
is required to consume this first stage.

## JSON system profile

```json
{
  "id": "shop",
  "environment": "TEST",
  "allowedHosts": ["api.internal", "registry.internal", "db.internal"],
  "assets": [
    {"id":"api","layer":"APPLICATION","kind":"URL",
     "location":"https://api.internal","openApi":"/private/specs/shop.json",
     "credentialRef":"customer"},
    {"id":"image","layer":"SERVICES","kind":"IMAGE",
     "location":"registry.internal/shop@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
    {"id":"database","layer":"NETWORK","kind":"HOST",
     "location":"db.internal","ports":[5432]},
    {"id":"tls","layer":"NETWORK","kind":"URL",
     "location":"https://api.internal"}
  ],
  "engines": [
    {"engine":"ZAP","enabled":true,"version":"2.16.1"},
    {"engine":"TRIVY","enabled":true,"version":"0.58.0"},
    {"engine":"NMAP","enabled":true,"version":"7.95"},
    {"engine":"TESTSSL","enabled":true,"version":"3.2.0"}
  ],
  "budget":{"maxRequests":500,"timeoutSeconds":600,"maxConcurrent":2}
}
```

Versions above illustrate syntax only, not recommended/current releases. Actual
engine installation, artifact provenance, vulnerability databases and rulesets
are not checked at this stage. Image references require explicit unambiguous
registry and sha256 digest. Repository/OpenAPI paths must be absolute normalized
local paths, but existence, symlinks and content are checked only by later stages.
No production environment, subnet discovery or arbitrary command syntax exists.

```java
SecurityAuditProfile profile = SecurityAuditProfiles.parse(json);
SecurityAuditPlan plan = planner.plan(profile);
```

The parser accepts at most 262144 characters, depth 32, string length 4096 and
number length 10. It rejects unknown/duplicate JSON fields, trailing content,
scalar coercion, null primitive values, invalid enum values and duplicate asset
or engine declarations with fixed diagnostics and no raw parser exception cause.
Profiles and assets redact toString. Locations must never be printed or copied
into reports. Raw credential fields are not part of the schema; credentialRef is
only a structural alias for a later resolver.

## Visible matrix and limits

All assets x all seven known engines appear, sorted by asset alias and stable enum
order. A missing engine declaration is disabled, never silently omitted.

| Job state | Meaning |
|---|---|
| PLANNED | Compatible enabled engine, required declaration metadata supplied |
| BLOCKED | Enabled applicable engine lacks allowed host, OpenAPI declaration, explicit ports or HTTPS |
| DISABLED | Compatible engine was not explicitly enabled |
| NOT_APPLICABLE | Engine does not support this asset's layer/type |

The plan is runnable only with at least one PLANNED job and no BLOCKED jobs.
`executed` is always false from this planner. It contains aliases, layer/type,
engine versions, status/fixed reason and requested bounds; no locations, ports,
local paths, credential references, payloads, secrets or executable commands.
It is not a security report and cannot be used as measured coverage or a gate.

Defaults/hard caps: 100/1000 assets, 700/7000 matrix jobs, 1000/10000 requests,
1200/3600 seconds, 2/8 concurrent jobs. Profile budget defaults to 1000 requests,
600 seconds and 2 jobs and must fit administrative ceilings. Each host can
specify at most 100 distinct ports (1..65535); only HOST accepts port lists.
URL/host/image registry names require an exact declared host allowlist for a job
to be PLANNED. URL credentials, query/fragment data, wildcard/CIDR hosts, encoded
paths and unnormalized traversal paths are refused. Network/URL ports are target
declarations, not permission to scan additional hosts or ports.

Runtime DNS/egress/redirect checks, credential resolution, file validation,
process isolation, raw scanner normalization and scanning are future
stages, not implied by successful declaration planning. No security completeness
claim is made. See SecurityAuditPlanningExampleTest and the independent published
JAR consumer for executable usage.

## Stage 2: reuse existing security assessments

`SecurityAuditImports.securityReport(asset, version, bytes, targetAliases)` accepts
**module-security's existing `security-findings.json` formatVersion 1**. It assigns
engine TESTFORGE, not ZAP. Other adapters can submit already-normalized v1 data
through `normalizedReport`; parsing raw ZAP/Trivy/Nmap output is later work.
There is no dependency on module-security/http/auth: this is a file-contract bridge.

```java
var evidence = imports.securityReport("api", "1.3.0-SNAPSHOT", upstreamBytes,
    Map.of("GET /protected", "protected-resource"));
var report = auditRunner.run(plan, List.of(evidence));
```

Enable TESTFORGE explicitly on an APPLICATION URL asset to import the native
checks. It does not require an OpenAPI declaration for already-produced evidence.
It adds a seventh engine to the visible matrix. Default/hard job ceilings are now
700/7000; other stage-1 bounds remain unchanged.

The importer validates the v1 shape, exact field sets, enums, count/coverage
consistency, bounds and finding identity. It projects the existing finding and
coverage shapes rather than introducing another security model. Every source
target in findings **and coverage** needs an explicitly supplied non-sensitive
alias; different targets cannot share one alias. This prevents source addresses,
object IDs or paths from entering audit artifacts. Role values and free-text
source evidence/acceptance/coverage reasons are withheld and replaced with null
or fixed descriptions. Consult the restricted upstream report for full existing
proof and acceptance reasons. Rule IDs and engine/version/target aliases remain
trusted non-sensitive metadata; this is not a universal secret detector for
arbitrary content disguised as normalized metadata.

Input is bounded strict JSON (no duplicate/unknown fields, trailing data, excessive
depth/strings/entries). Invalid, oversized or unmapped data returns incomplete
import evidence with a fixed diagnostic. No raw input or parser cause is retained.
Valid partial upstream reports retain their findings while remaining incomplete.
Malformed reports are not accepted as finding evidence.

`SecurityAuditRunner.assess(plan, evidence)` is pure aggregation.
`run(plan, evidence)` additionally writes JSON/Markdown in a fresh private
`results.output-dir/audit-*/` directory and registers both through ArtifactSink.
Only the current invocation's files are registered. Output-directory ancestors
must be canonical/non-symlink paths (on macOS, resolve temporary paths with
`toRealPath` before configuring them). Configuration:

```yaml
forge:
  security-audit:
    enabled: true
    results:
      output-dir: build/security-audit
      max-bytes: 2097152  # per input/report file, and total projected evidence
      max-entries: 10000  # combined findings + coverage across imported sources
```

The envelope has formatVersion 1 and mode IMPORTED. Its jobs retain layer, asset,
requested/evidence engine versions and the projected security report v1. JSON
Schema is packaged as `/security-audit.schema.json`. No scanner has been launched
by this stage. Importing a report does not independently attest its origin,
freshness, authentication or full system scope; the caller/adapter must supply
matching current evidence. Later execution must enforce those prerequisites.

A planned job without one matching source, an invalid/partial source or version
mismatch is INCOMPLETE. Duplicates cannot establish completion. Evidence for
unplanned/disabled jobs is a configuration error; missing plan cells and aggregate
bounds fail closed. Existing findings are retained on valid partial/version-
mismatched evidence. Blocked jobs prevent completion; disabled/inapplicable jobs
remain visible. With no measured evidence the report is never complete/healthy.

Healthy is the conjunction of all completed upstream verdicts and aggregate
completeness, not a second severity policy. New/accepted/resolved and newCounts
reuse upstream semantics. Baseline comparison/promotion and severity thresholds
remain in SecurityRunner; the audit runner exposes no baseline promotion or
independent assertAcceptable method. An accepted artifact-secret finding cannot
produce a healthy import. No cross-engine deduplication or implicit acceptance.

Tests: SecurityAuditResultsTest; SecurityAuditResultsExampleTest imports an actual
SecurityRunner report before/after its explicit writeBaseline and verifies the
baseline file is unchanged by the audit. The published consumer's
`securityAuditStandaloneTest` uses only audit/core JARs plus the test framework and
verifies the original security/http classes are absent.

## Stage 3: explicit process execution and CLI

`SecurityAuditExecutor` is separate from the pure planner and report importer.
It is inert until **both** `forge.security-audit.enabled=true` and
`forge.security-audit.execution.enabled=true`. Supply trusted `SecurityAuditAdapter`
beans explicitly (one per engine) and, when needed, a
`SecurityAuditExecutionContext.Secrets` bean. No scanner adapters or service-loader
plugins are bundled in this stage. Profile/CLI data cannot specify executables,
shell commands, environment variables or adapter classes.

```yaml
forge:
  security-audit:
    enabled: true
    execution:
      enabled: true
      work-dir: build/security-audit-work
      max-output-bytes: 2097152
      cleanup-seconds: 3
      max-children: 32
```

`executor.run(profile)` replans the entire scope, checks enabled assets' declared
local input roots/files and refuses blocked prerequisites before preparation or
credential resolution. An absent adapter or registered-version mismatch is
explicit incomplete evidence. Adapter version is trusted registration metadata,
not independent binary-version verification; each real adapter must prove its
actual pinned scanner version in its acceptance stage.

An adapter's `prepare(context)` must be bounded, interruptible and side-effect
free: it constructs a private `SecurityAuditCommand` with canonical executable,
structured arguments, bounded stdin and explicit source-target aliases. The
executor alone launches it. Argument count/length, stdin, normalized report and
combined stdout+stderr are bounded. Successful exit codes default to `{0}`; an
adapter can explicitly accept finding-bearing codes such as `{0,2}`. A nonzero
exit not in that reviewed policy is incomplete, not a clean result.

Each process receives a fresh private working directory, HOME/TMPDIR there, and
only fixed LANG/LC_ALL environment values. The runtime/OS may inject its own
platform metadata (e.g. macOS text encoding); the parent environment, token env
vars, HTTP defaults and logging filters are not inherited. Credentials are
resolved just before preparation, passed via context/stdin and cleared on close;
adapters must clear any temporary credential copies they create. Known exact or
JSON-escaped credentials in stdout, and exact credentials in executable/argv or
target aliases, block import. Stderr is counted and discarded, never persisted.
This is not a detector for every encoded/derived representation of a secret.

The profile timeout is shared across all jobs, including preparation/startup.
Cancellation is polled during preparation and process execution. Caller interrupt
status is restored after cleanup/reporting. Cleanup grace is additional to scan
time; it is bounded per cleanup step. Later jobs cannot start once cancelled or
out of budget. Jobs currently run **sequentially**: actual concurrency is one,
within the declared ceiling; parallel scheduling is deferred.

Observed descendants are terminated before the parent where possible, with
bounded graceful/forced shutdown and I/O completion. Failed or uncooperative
preparation/cleanup is explicit CLEANUP_FAILED; late returned command input is
cleared and never launched. Owned workspace deletion does not follow symlinks
and is bounded to depth 32 / 10000 entries. Uncertain workspaces are retained
privately rather than counted as cleaned. Configured work/output roots must have
canonical non-symlink ancestors. Input file checks reject symlinks and oversized
OpenAPI files (4 MiB); repository root checks do not establish safety of every
file a future tool might read.

**Boundary:** `PRIVATE_IO_NO_OS_SANDBOX` is not a container, network firewall,
filesystem jail, CPU/disk quota or process-group guarantee. ProcessHandle tracking
cannot guarantee capture of escaped/reparented/unobserved children; observed-child
limits are monitoring limits, not fork prevention. Arbitrary adapter Java code
is trusted code, not sandboxed. Network/DNS pinning, redirects, external scanner
egress, mutation rules and per-request budgets are **not enforced by this generic
process boundary**. Actual scanner adapters must implement/prove those controls
(e.g. reviewed gateway/container) before use; no real scanner is bundled here.

The executor reuses the stage-2 assessment/report and adds a separate
`security-audit-execution.json` receipt (schema
`/security-audit-execution.schema.json`, referencing the existing assessment
schema). Receipt fields are fixed outcomes, started/exit/time/byte metadata and
cleanupComplete; no PID, argv, environment values, locations, stdin or raw console
output is exported. `cleanupComplete` covers observed processes, preparation and
owned workspace cleanup, not containment of unknown escaping descendants.

### File-output command

```bash
./gradlew :module-security-audit:securityAudit \
  -PauditCommand=plan -PauditProfile=/absolute/profile.json \
  -PauditOutput=/absolute/private-output

./gradlew :module-security-audit:securityAudit \
  -PauditCommand=run -PauditRun=true -PauditProfile=/absolute/profile.json \
  -PauditOutput=/absolute/private-output
```

Library CLI entry point:
`SecurityAuditCli plan|run --profile /absolute/profile.json --output /absolute/output`.
Run additionally needs `--enable-run`. Profiles are read through a bounded
non-symlink file channel, strict UTF-8/JSON; unknown/duplicate options are refused.
The CLI writes files rather than printing private diagnostics. Exit codes:
0 = plan written or complete healthy assessment; 2 = complete unhealthy
assessment; 3 = incomplete/configuration/I/O failure. A valid blocked plan can be
written with exit 0; inspect runnable/status fields. **Default CLI registry is
empty: run produces ADAPTER_MISSING/incomplete, not a pretend scan.** Trusted
applications register adapters through Java/Spring; CLI/profile cannot do so.

Examples/tests: SecurityAuditExecutionExampleTest uses an owned Java subprocess,
SecurityAuditExecutorTest exercises timeout/cancel/children/floods/secrets/late
preparation, and SecurityAuditCliTest checks dry run and exit semantics. The
published standalone consumer exercises CLI without security/http modules.
Real ZAP acceptance remains the next stage.

The portable process layer also does not stop daemon-managed resources such as
Docker containers simply by killing a CLI. A real adapter must prove cleanup of
its external resources; that lifecycle is part of scanner-specific acceptance.
Gradle JavaExec reports a failed task for child exit 2/3; exact 0/2/3 codes apply
to the Java CLI entry point, while Gradle itself uses its standard failure code.
