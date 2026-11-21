# module-security-audit: working on this module

Read root AGENTS.md and README.md first. Design and ordered acceptance:
[security-audit-roadmap.md](../docs/security-audit-roadmap.md).

Scope now: private profile, pure declaration planner, bounded import of existing
security-v1 assessments, explicit reports and trusted private-I/O process/CLI
foundation. Dependencies: core only. No mandatory module-security, module-quality,
module-reporting, scanner libraries or Docker dependencies. Existing engine
integrations must be reused through an explicit optional seam in later work.

Invariants:
- Inert by default. Planning never performs DNS, filesystem inspection, token
  fetches, process launch or HTTP; declaring a path is not checking that file.
- PLANNED/runnable is not measured security coverage; plans have executed=false.
- Include every asset x engine cell with fixed reasons, even disabled/unsupported.
- Never serialize a private profile into an artifact. Locations, credentials,
  ports and local paths must not enter plans, errors or toString. Aliases are
  explicitly non-sensitive metadata; do not invent identifiers from target data.
- No arbitrary shell command, implicit host discovery or unpinned enabled engine.
  No credential material in config: references only. Network/engine-specific
  runtime guarantees remain later work; do not imply syntax validation establishes reachability or confinement.
- Parse bounded strict JSON without leaking parser input or exception causes.
- Keep the module removable and explicit dependency edges. Use explicit imports
  and 4-space indentation; no Thread.sleep or production standard streams.

Before finishing: :module-security-audit:test, :architecture-tests:test and full
build offline; update example/README/CHANGELOG and stage status. Published-JAR
consumer complements source examples. New engine adapters need pinned actual
versions, broken/fixed owned fixtures, sanitized output and timeout/cleanup tests;
mocked execution alone is not acceptance of a real scanner integration.

Stage 2 adds SecurityAuditImports/Runner/Report and a versioned JSON envelope over
existing security v1. Keep the file-contract bridge and core-only dependencies;
no duplicate baseline, severity policy or automatic acceptance. Native checks are
engine TESTFORGE, never mislabeled ZAP. Require explicit injective target aliases;
withhold source role/free-text evidence/reasons. Preserve valid partial findings;
malformed evidence is incomplete, not trusted findings. Input and aggregate/output
bounds fail closed. Public JsonNode accessors return copies. Reports are mode
IMPORTED, not an attestation of execution/freshness or full API coverage. Register
only the new private invocation directory's files. No symlink ancestors; no raw
source diagnostics/cause strings. Update security-audit.schema.json with contract
changes and verify it against actual serialized reports. Tests: ResultsTest,
ResultsExampleTest and published standalone consumer. Scanner execution is still
stage 3+, not a consequence of report aggregation.

Stage 3: SecurityAuditExecutor/Adapter/Command and SecurityAuditCli. Additional
execution opt-in; no bundled engines or adapter discovery from profile/CLI.
Trusted adapter preparation is bounded/interruptible and may not launch anything.
Only executor launches structured canonical binaries, with private cwd, cleared
env, stdin credentials, combined output bound and overall monotonic deadline.
Never persist raw stdout/stderr, argv, PIDs, credentials or private context.
Success exit codes are adapter-reviewed; findings exits are not automatically
crashes. Cancellation/timeout/late preparation/failed cleanup stay incomplete.
Jobs are sequential; concurrency ceiling is not a promise of parallel work.
No OS/network sandbox claim: PRIVATE_IO_NO_OS_SANDBOX. Only observed descendants
can be tracked; real adapters still need proven gateway/container scope, DNS,
request/egress/mutation controls. Keep escaping/unobserved-process limitations
visible. Receipt schema refers to the existing audit schema; no new baseline or
severity policy. Tests: ExecutorTest, CliTest, ExecutionExampleTest, published CLI.
