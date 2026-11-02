# Internal security audit aggregator

Status: local development, not a released capability. Work proceeds one numbered
stage at a time. Stages 1-3 are the current implementation boundary; later stages
are a roadmap, not features already available.

## Objective

Run repeatable security assessments of an explicitly configured internal system,
across application, service and network layers. Use established scanning engines,
collect their evidence, explain coverage and highlight findings in one result.
A normal run must not require a model, hosted interpretation service or paid
inference. Initial scope is web/API services and their deployment components;
mobile, desktop, cloud account audits and full penetration testing are separate.

This is an internal orchestration module, not a new attack engine. Its value is
system inventory, credentials, bounded execution, consistent evidence, coverage,
regressions and integration with existing workflows.

## Layers and engine selection

| Layer | Question | Engine | Required inputs |
|---|---|---|---|
| Application | What does the running API/web application expose or accept? | ZAP first; Schemathesis later | Explicit base URL, local OpenAPI, optional credential reference, test fixtures when needed |
| Services | Are packages/images/configurations vulnerable or misconfigured? | Trivy first | Immutable image reference or a local repository |
| Services | Are known vulnerable HTTP components or administrative surfaces exposed? | Nuclei later | Explicit service URL and reviewed template set |
| Network | Which configured ports/services are reachable from this runner? | Nmap | Explicit host and bounded port list |
| Network / transport | What TLS protocols, ciphers and certificate problems are exposed? | testssl.sh | Explicit HTTPS endpoint and scan profile |
| Optional source extension | Does source code contain insecure patterns? | Semgrep CE, deferred | Repository, language and reviewed rules |

Tools can overlap; the layer is the reason for checking an asset, not an assertion
that each tool operates at exactly one protocol layer. URL-only access does not
establish the security of source code, dependencies, images or internal networks.
A runner's network position matters and belongs in future execution metadata.
An open port is an observed asset, not automatically a vulnerability. A version
match is not automatically proof of exploitability. Schema or server errors from
fuzzing are not automatically exploitable security vulnerabilities.

Primary references (researched 2026-11-02; recheck versions/formats before adapter work):

- ZAP automation: https://www.zaproxy.org/docs/automate/automation-framework/
- ZAP API scan: https://www.zaproxy.org/docs/docker/api-scan/
- Schemathesis checks: https://schemathesis.readthedocs.io/en/latest/reference/checks/
- Trivy: https://www.trivy.dev/
- Nuclei: https://docs.projectdiscovery.io/opensource/nuclei/running
- Nmap XML: https://nmap.org/book/man-output.html
- testssl.sh: https://github.com/testssl/testssl.sh
- DefectDojo imports: https://docs.defectdojo.com/supported_tools/

## Module boundary

`module-security-audit`, package `io.testforge.security.audit`, depends on core
only in stage 1. It has its own opt-in configuration, public records, guide and
examples. It does not pull scanner binaries, Docker or scanner-specific Java
libraries onto the application classpath. Production modules remain removable.

Existing `module-security` keeps passive/auth/business checks and its reviewed
ZAP integration. `module-api-fuzz` keeps its Schemathesis integration. Before
adding those adapters, choose and prove an optional bridge/shared seam that
reuses the existing isolation, redaction and controls rather than creating a
second divergent transport. No mandatory module-quality/reporting dependency:
use ArtifactSink/file contracts; a quality adapter comes only after the result
format is defined. Do not count a planned job as measured security coverage.

For internal use, start with a library plus a thin command-line entry point.
Do not build a web UI, account management or ticketing system in the MVP.
Evaluate self-hosted DefectDojo for long-lived finding management. Its community
report imports are a possible destination; paid connectors are not assumed.

## Architecture and contracts

Profile -> validated asset/engine matrix -> bounded execution -> adapter result
-> normalized findings + explicit coverage -> report -> optional history/gate/export.

The profile declares system alias, environment, exact host allowlist, assets,
engine enablement/version and run budget. Assets carry structural aliases,
location, optional local OpenAPI path and credential-reference alias. Runtime
credentials are resolved just in time by a later executor; no literal credential
field exists. The first version supports single hosts and explicit ports only,
not arbitrary subnet discovery or automatically following newly discovered hosts.

Profiles contain internal addresses/paths and must be treated as private config.
Even valid profiles are not safe report payloads. Their toString is redacted.
Plans contain aliases, engine/layer/kind, version, bounds and fixed reasons only;
no actual target URLs, paths, ports, credential aliases/material or commands.
Planning validates declaration syntax only: it does not resolve DNS, inspect local
files, check installed tools or establish that a target is reachable. A PLANNED
job must pass fresh execution-time checks before any engine can run.

Later normalized finding contract must retain engine/rule/version, component and
asset aliases, structural location, severity, confidence/evidence class, CWE/CVE
when supplied, redacted evidence and remediation reference. Retain provenance
when tools overlap. Do not merge findings just because their titles match.

Coverage distinguishes planned, running, measured, not applicable, disabled,
blocked and incomplete as appropriate to each lifecycle. Missing authentication,
zero successful operations, malformed output, timeout or partial scan must never
become a clean security verdict or resolve old findings. Disabled/inapplicable
scope must remain visible rather than silently disappear.

## Execution policy

- Inert by default. Stage 1 cannot send traffic or launch processes.
- Initial supported environments: LOCAL, TEST, STAGING. Production operation is
  not part of this first scope.
- Fixed scanner versions; immutable image digests; pinned template/rule sets and
  captured vulnerability database revisions when adapters are implemented.
- Explicit target scope, methods, selected rules, request/time/parallelism budgets.
  CLI flags alone do not establish enforcement: verify each adapter's actual
  boundaries and report what cannot be enforced.
- Reads/baseline and mutations need distinct execution profiles. No implied
  permission to mutate from HTTP method names or OpenAPI import. Mutation scans
  require disposable fixtures and cleanup policy; out-of-band callbacks require
  separately configured internal infrastructure and egress scope.
- Validate DNS/IP destination, redirects, local paths/symlinks, scanner egress,
  child processes and mounted paths at execution time; disable automatic target
  expansion, updates, telemetry and hosted analysis where supported.
- No arbitrary shell command in config. Arguments are structured, credentials
  cannot enter argv, plans or reports. Scanner files are private and bounded;
  raw bodies/console output may contain secrets and cannot be uploaded directly.
- Timeout, cancellation, process-tree cleanup and resource exhaustion are explicit
  incomplete outcomes. No implicit rerun of a potentially mutating request.
- Inspect engine/rule licenses for the chosen deployment/packaging model. Do not
  assume a tool's source license also covers its rules, feeds or redistribution.

## Ordered work and acceptance

| Stage | Deliverable | Acceptance | Status |
|---|---|---|---|
| 1 | Module skeleton, strict JSON system profile, pure deterministic planner | Valid three-layer profile yields a complete visible matrix; invalid targets/aliases/budgets fail with fixed diagnostics; missing inputs are blocked; no network/process/file inspection; plan contains no locations/secrets; offline example and published-JAR consumer pass | DONE LOCALLY |
| 2 | Versioned result/finding/coverage contract and report writers | Reuse security report v1 through bounded projection and explicit target aliases; JSON/Markdown + schema; partial/failed sources cannot pass; ArtifactSink registration; actual native baseline reuse; standalone consumer without security/http | DONE LOCALLY |
| 3 | Adapter interface, private-I/O executor and plan/run command | Owned Java processes prove timeout/cancel/output/observed-child cleanup, missing binary/adapter, reviewed exit codes, malformed output, local-input/allowlist refusal and secret handling; explicit NO_OS_SANDBOX receipt; dry run prepares nothing | IMPLEMENTED LOCALLY |
| 4 | ZAP application adapter | Reuse reviewed existing integration; extend selected rules/scope only with acceptance evidence; auth failure and unvisited operations are incomplete; corrected/broken local API results are distinguishable; pin actual engine/rules | PLANNED |
| 5 | Trivy service adapter | Local image/repository scan, explicit scanners and database revision; known vulnerable/fixed fixtures; no implicit registry fetch outside scope; vulnerability/misconfiguration/secret evidence normalized without leaking secret values | PLANNED |
| 6 | Nmap and testssl.sh network adapters | Explicit hosts/ports, runner vantage, hardened XML/JSON parsing, TLS endpoint checks; open ports remain inventory unless a declared exposure policy is violated; no guessed CVEs from banners alone | PLANNED |
| 7 | Nuclei and Schemathesis optional depth | Reviewed pinned templates, no default broad scan; existing fuzz integration reused; callbacks/mutations/extra requests are accounted for; false-positive controls and broken/fixed fixtures per check family | PLANNED |
| 8 | History, triage and gate | Stable finding identity with provenance; accepted findings require reason/expiry; incomplete scans never resolve findings; severity/confidence/exposure remain separate; export to DefectDojo evaluated before building storage/UI | PLANNED |
| 9 | Internal pilot and operational handoff | Run on at least two independently configured internal systems using published artifacts; measure setup time, coverage, detections, false positives, duration and maintenance effort; document unsupported cases and decide MVP boundary | PLANNED |

Each stage updates this table, README, tests and CHANGELOG. No claim about later
stages is made by a successful stage-1 build. Work is local until explicitly
instructed otherwise; no automatic commit/push/PR/merge or external scans.

## First-stage API and example

`SecurityAuditProfiles.parse(json)` parses a bounded strict JSON profile.
`SecurityAuditPlanner.plan(profile)` returns a value-free matrix of all assets
against all known engines, including native TESTFORGE checks. `forge.security-audit.enabled=true` enables planning;
this opt-in does not launch any engine. Job states:

- PLANNED: declaration is compatible and required metadata is present;
- BLOCKED: enabled applicable engine lacks prerequisites or permitted target;
- DISABLED: compatible engine was not enabled;
- NOT_APPLICABLE: engine does not support this asset's layer/type.

A runnable plan has at least one PLANNED job and no BLOCKED jobs. It is still
`executed=false` and provides no measured security result. Engine policies carry
an exact release version; artifact/template provenance and runtime verification
are later work. See module-security-audit/README.md for the profile format.

## Cost and success metrics

Initial planning estimates for one experienced developer: prototype 1-2 weeks,
limited operational MVP 4-6 weeks, with no UI and a fixed set of environments.
These are not measured delivery commitments; stage 4/5 pilots should revise them.
The full nine-stage roadmap, particularly six engines and robust isolation, may
exceed the MVP budget. Measure useful detected issues, actual exercised scope,
false positives and onboarding effort; finding counts alone are not success.

## Explicit deferrals

No custom injection engine, generic automatic business-policy inference,
concurrent race executor, production pentest claim, arbitrary subnet discovery,
endpoint agent, commercial SaaS, mobile/desktop scanning or custom dashboard in
this scope. Existing local invariant/idempotency work remains separate and is
not a prerequisite for the aggregator's profile/planner.

## Stage-2 reuse decision

Keep SecurityFinding/SecurityCoverage/SecurityReport/Severity and SecurityRunner
unchanged. Import their version-1 JSON file contract instead of moving classes,
pulling HTTP/auth into the aggregator, or creating another baseline mechanism.
Audit envelope v1 adds job provenance and mode IMPORTED; each nested security
assessment keeps the original v1 shapes with target aliases and withheld free
text/roles. Counts/statuses/coverage semantics are validated and reused. Native
checks have engine TESTFORGE, a seventh matrix source; no scanner is launched.
Explicit aliases are required for every source target. A normalized source is a
trusted producer boundary, not proof of execution, freshness or complete system
coverage. Confidence/CVE/database/ruleset metadata missing from v1 is not invented;
engine adapters will define explicit versioned extensions where needed.

Result schema and writing are now implemented. Execution, actual scanner adapters,
aggregate history/deduplication and CI policy remain later stages.

## Stage-3 process boundary

Explicit trusted adapters only; the default CLI bundles none. Jobs execute
sequentially with shared deadline, bounded preparation/I/O and private cwd/env.
Receipt isolation is PRIVATE_IO_NO_OS_SANDBOX: observed-descendant cleanup is
best effort, not process-group containment. No generic network/DNS/request/egress
confinement or CPU/disk quota is claimed. Real scanner adapters must prove these
controls in their own stages. CLI run without an adapter is incomplete; plan
launches nothing. Existing normalized assessment and baseline semantics remain.
