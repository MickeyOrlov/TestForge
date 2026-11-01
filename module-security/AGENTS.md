# module-security: working on this module

Read root AGENTS.md and README.md first.

Scope: security findings, explicit coverage and gates; passive API response
checks; subsequent opt-in probes and delegated scans.
May depend on core, module-http, module-auth and module-api-explorer. No dependency on module-quality/reporting.

Style: like the rest of the repository — 4-space indentation, explicit imports
(no wildcards), 8-space continuation; google-java-format `--aosp` produces it.

Invariants:
- Inert by default. Passive checks add no traffic and never replace a response.
- Never retain observed values in findings, coverage, logs, errors or SARIF.
- Missing/incomplete coverage never passes the gate or resolves old findings.
- Accepted IDs require reasons; baseline promotion is explicit.
- Additional traffic requires explicit configured test targets and bounded work.

Tests: SecurityPassiveTest, SecurityAuthTest, SecurityPassiveExampleTest and
SecurityAuthExampleTest, SecurityTransportTest, SecurityArtifactTest,
SecurityTransportExampleTest and SecurityArtifactExampleTest. Before finishing:
`./gradlew :module-security:test :architecture-tests:test`, then `./gradlew build`.
Update README/examples/CHANGELOG with behavior changes. Keep the module removable.

B4: never weaken global JDK TLS settings or treat a locally-disabled algorithm
as server rejection. B5: no following symlinks, unbounded reads or secret values
in diagnostics; leaks block upload even when ordinary findings are accepted.

B3: delegate through the loopback read-only gateway, never document servers.
Only reviewed rules and pinned images; startup/timeouts/cleanup failures are
incomplete. Never persist raw console/report evidence or put credentials in
process arguments/plans. Read SecurityActiveTest and SecurityZapIT before edits.
Run :example-tests:securityScanTest for actual scanner/transport changes.
Aggregate reports expose measured and complete separately; an enabled but
unexecuted block cannot be hidden by other measurements. A4 consumes this
contract and remains part of module-quality.
